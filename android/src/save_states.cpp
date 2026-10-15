#include "stdafx.h"
#include "save_states.h"
#include "Emu/System.h"
#include "Emu/savestate_utils.hpp"
#include "Utilities/File.h"
#include "Crypto/sha256.h"

#include <array>
#include <algorithm>
#include <memory>
#include <atomic>
#include <filesystem>

LOG_CHANNEL(save_state_log, "SAVESTATE");

namespace
{
    constexpr unsigned slot_count = 10;

    struct operation
    {
        long progress_id;
        unsigned slot;
        std::string title;
        bool save;
        std::atomic<bool> stop_requested{false};
        bool written = false;
        std::string path;
        std::atomic<bool> awaiting_run{false};
        bool captured = false;
        u64 resume_identifier = 0;

        operation(long id, unsigned index, std::string game_title, bool saving, std::string file)
            : progress_id(id), slot(index), title(std::move(game_title)), save(saving), path(std::move(file)) {}
    };

    // Every access is protected by the same mutex as boot, install and shutdown.
    std::shared_ptr<operation> pending;
    std::shared_ptr<operation> stoppable;
    std::mutex stop_mutex;

    std::shared_ptr<operation> active_operation()
    {
        std::lock_guard lock(stop_mutex);
        return stoppable;
    }

    bool valid_title(const std::string& title)
    {
        return !title.empty() && title.size() <= 128 &&
            std::all_of(title.begin(), title.end(), [](unsigned char ch)
            {
                return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') ||
                    (ch >= '0' && ch <= '9') || ch == '-' || ch == '_';
            });
    }

    std::string slot_directory(const std::string& title)
    {
        return fs::get_config_dir() + "savestates/" + title + "/emucorec_slots/";
    }

    std::string find_slot(const std::string& title, unsigned slot)
    {
        if (!valid_title(title) || slot >= slot_count) return {};
        const std::string base = slot_directory(title) + "slot" + std::to_string(slot) + ".SAVESTAT";
        for (const char* extension : {".zst", ".gz", ""})
        {
            const auto path = base + extension;
            if (fs::is_file(path)) return path;
        }
        return {};
    }

    struct integrity_record
    {
        u64 size = 0;
        std::array<u8, 32> digest{};

        bool operator==(const integrity_record&) const = default;
    };

    // A manifest accepts one committed payload, or both the previous and the
    // staged payload during replacement. Fixed-size records bound untrusted I/O.
    constexpr std::string_view integrity_magic = "EMUCSH01";
    constexpr usz integrity_record_size = 8 + 32;
    constexpr usz integrity_header_size = 8 + 1;
    constexpr usz integrity_max_size = integrity_header_size + 2 * integrity_record_size;

    std::vector<integrity_record> read_integrity(const std::string& path)
    {
        fs::file file(path + ".integrity");
        if (!file) return {};
        const u64 size = file.size();
        if (size != integrity_header_size + integrity_record_size && size != integrity_max_size)
            return {};
        std::array<u8, integrity_max_size> bytes{};
        if (file.read(bytes.data(), size) != size ||
            std::memcmp(bytes.data(), integrity_magic.data(), integrity_magic.size()) != 0)
            return {};
        const u8 count = bytes[8];
        if (count < 1 || count > 2 || size != integrity_header_size + count * integrity_record_size)
            return {};
        std::vector<integrity_record> result(count);
        for (usz i = 0; i < count; ++i)
        {
            const u8* data = bytes.data() + integrity_header_size + i * integrity_record_size;
            for (usz byte = 0; byte < 8; ++byte)
                result[i].size |= u64{data[byte]} << (byte * 8);
            std::memcpy(result[i].digest.data(), data + 8, result[i].digest.size());
        }
        return result;
    }

    bool write_integrity(const std::string& path, const std::vector<integrity_record>& records)
    {
        if (records.empty() || records.size() > 2) return false;
        std::array<u8, integrity_max_size> bytes{};
        std::memcpy(bytes.data(), integrity_magic.data(), integrity_magic.size());
        bytes[8] = static_cast<u8>(records.size());
        for (usz i = 0; i < records.size(); ++i)
        {
            u8* data = bytes.data() + integrity_header_size + i * integrity_record_size;
            for (usz byte = 0; byte < 8; ++byte)
                data[byte] = static_cast<u8>(records[i].size >> (byte * 8));
            std::memcpy(data + 8, records[i].digest.data(), records[i].digest.size());
        }
        const usz size = integrity_header_size + records.size() * integrity_record_size;
        fs::pending_file output;
        return output.open(path + ".integrity") && output.file.write(bytes.data(), size) == size && output.commit();
    }

    bool hash_payload(fs::file& source, integrity_record& record, fs::file* copy = nullptr)
    {
        struct digest_context
        {
            mbedtls_sha256_context context;
            digest_context() { mbedtls_sha256_init(&context); }
            ~digest_context() { mbedtls_sha256_free(&context); }
        } hash;
        record.size = source.size();
        if (!record.size || record.size == umax || source.seek(0) != 0 ||
            mbedtls_sha256_starts_ret(&hash.context, 0) != 0) return false;
        std::array<u8, 65536> buffer{};
        for (u64 copied = 0; copied < record.size;)
        {
            const u64 count = std::min<u64>(buffer.size(), record.size - copied);
            if (source.read(buffer.data(), count) != count ||
                mbedtls_sha256_update_ret(&hash.context, buffer.data(), count) != 0 ||
                (copy && copy->write(buffer.data(), count) != count)) return false;
            copied += count;
        }
        return source.size() == record.size && mbedtls_sha256_finish_ret(&hash.context, record.digest.data()) == 0;
    }

    bool verified_payload(const std::string& path, integrity_record* verified = nullptr)
    {
        const auto records = read_integrity(path);
        if (records.empty()) return false;
        fs::file file(path);
        if (!file) return false;
        const u64 size = file.size();
        if (std::none_of(records.begin(), records.end(), [size](const auto& record) { return record.size == size; }))
            return false;
        integrity_record actual;
        if (!hash_payload(file, actual) || std::find(records.begin(), records.end(), actual) == records.end())
            return false;
        if (verified) *verified = actual;
        return true;
    }

    bool owned_slot_path(const std::string& path)
    {
        const auto normalized = std::filesystem::path(path).lexically_normal();
        const auto directory = normalized.parent_path();
        const auto title = directory.parent_path().filename().string();
        const auto owned_directory = (std::filesystem::path(fs::get_config_dir()) /
            "savestates" / title / "emucorec_slots").lexically_normal();
        if (!valid_title(title) || directory.filename() != "emucorec_slots" ||
            directory != owned_directory)
            return false;
        const auto name = normalized.filename().string();
        for (unsigned slot = 0; slot < slot_count; ++slot)
        {
            const auto base = "slot" + std::to_string(slot) + ".SAVESTAT";
            if (name == base || name == base + ".zst" || name == base + ".gz") return true;
        }
        return false;
    }

    bool capture_slot(const operation& op, std::string& destination)
    {
        if (!op.written || op.path.empty()) return false;
        fs::file source(op.path);
        // The core's completed history writer always produces zstd. Keeping
        // that extension also makes a new slot win over obsolete variants.
        if (!source || !op.path.ends_with(".SAVESTAT.zst")) return false;
        const std::string directory = slot_directory(op.title);
        if (!fs::create_path(directory)) return false;
        const auto base = directory + "slot" + std::to_string(op.slot) + ".SAVESTAT";
        destination = base + ".zst";

        // A short write or failed rename must leave the previous slot intact.
        fs::pending_file output;
        if (!output.open(destination)) return false;
        integrity_record candidate;
        if (!hash_payload(source, candidate, &output.file)) return false;
        std::vector<integrity_record> accepted;
        integrity_record previous;
        // Never bless an existing, potentially damaged file by freshly hashing
        // it: its old committed manifest must already authenticate those bytes.
        fs::stat_t previous_stat{};
        if (fs::get_stat(destination, previous_stat))
        {
            // A read failure must not discard the old authentication record
            // before a possibly failing payload rename. Delete damaged slots
            // explicitly before replacing them with a newly captured state.
            if (!verified_payload(destination, &previous)) return false;
            if (previous != candidate) accepted.push_back(previous);
        }
        else if (fs::g_tls_error != fs::error::noent) return false;
        accepted.push_back(candidate);
        if (!write_integrity(destination, accepted)) return false;
        // A failed payload rename leaves the previous payload in place, still
        // matching the previous record in the already-published manifest.
        if (!output.commit()) return false;
        // Publication is complete. Failed compaction merely leaves two valid
        // records; do not turn a committed overwrite into a reported failure.
        try
        {
            if (accepted.size() > 1 && !write_integrity(destination, {candidate}))
                save_state_log.warning("Could not compact slot integrity metadata: %s", destination);
            for (const char* extension : {".gz", ""})
            {
                const auto obsolete = base + extension;
                if (fs::is_file(obsolete)) fs::remove_file(obsolete);
                if (fs::is_file(obsolete + ".integrity")) fs::remove_file(obsolete + ".integrity");
            }
        }
        catch (const std::exception& error)
        {
            save_state_log.warning("Could not compact slot integrity metadata: %s", error.what());
        }
        return true;
    }

    void finish(std::shared_ptr<operation> op, bool success)
    {
        if (pending != op) return;
        // Retire the handle before the last stop check: a concurrent exit
        // either sets this flag in time or falls through to normal shutdown.
        bool stop;
        {
            std::lock_guard lock(stop_mutex);
            stoppable.reset();
            stop = op->stop_requested.load();
        }
        pending.reset();
        if (stop && !op->save) success = false;
        report_native_operation_result(op->progress_id, success,
            success ? "Completed" : "The save state operation could not be completed");
        if (stop)
        {
            Emu.SetContinuousMode(false);
            Emu.after_kill_callback = nullptr;
            Emu.Kill();
        }
    }

    template <typename F>
    void run_operation(const std::shared_ptr<operation>& op, F&& work)
    {
        std::lock_guard lock(g_emu_lifecycle_mutex);
        if (pending != op) return;
        try { work(); }
        catch (const std::exception& error)
        {
            save_state_log.error("Save state operation failed: %s", error.what());
            op->stop_requested = true;
            finish(op, false);
        }
        catch (...)
        {
            save_state_log.error("Save state operation failed with an unknown exception");
            op->stop_requested = true;
            finish(op, false);
        }
    }

    bool request(unsigned slot, long progress_id, bool save)
    {
        if (save_states::busy()) return false;
        std::lock_guard lock(g_emu_lifecycle_mutex);
        if (pending || slot >= slot_count || Emu.IsStateChangePending() ||
            (!Emu.IsRunning() && !Emu.IsPaused())) return false;
        const auto title = Emu.GetTitleID();
        if (!valid_title(title)) return false;
        auto op = std::make_shared<operation>(progress_id, slot, title, save, std::string{});
        pending = op;
        {
            std::lock_guard lock(stop_mutex);
            stoppable = op;
        }
        Emu.CallFromMainThread([op]
        {
          run_operation(op, [op]
          {
            if (Emu.IsStateChangePending() || (!Emu.IsRunning() && !Emu.IsPaused()))
            {
                finish(op, false);
                return;
            }
            if (op->stop_requested)
            {
                finish(op, false);
                return;
            }
            if (!op->save)
            {
                op->path = find_slot(op->title, op->slot);
                // Validate before stopping the current game, including version mismatches.
                bool compatible = false;
                try { compatible = !op->path.empty() && save_states::validate_slot_file(op->path) && is_savestate_compatible(op->path); }
                catch (const std::exception& error) { save_state_log.error("Cannot validate save state: %s", error.what()); }
                if (!compatible)
                {
                    finish(op, false);
                    return;
                }
            }
            Emu.SetContinuousMode(true);
            Emu.after_kill_callback = [op]
            {
              run_operation(op, [op]
              {
                std::string path = op->path;
                const bool saved = !op->save || capture_slot(*op, path);
                if (op->stop_requested)
                {
                    Emu.SetContinuousMode(false);
                    finish(op, op->save && saved);
                    return;
                }
                // Even when copying a slot fails, resume from the completed history file.
                // A failed capture never overwrites a previous numbered slot.
                const std::string resume_path = op->save ? op->path : path;
                op->awaiting_run = true;
                op->captured = saved;
                op->resume_identifier = static_cast<u64>(Emu.GetEmulationIdentifier());
                const bool restored = !resume_path.empty() &&
                    Emu.BootGame(resume_path, "", true) == game_boot_result::no_errors;
                if (!restored)
                {
                    Emu.SetContinuousMode(false);
                    op->stop_requested = true;
                    finish(op, false);
                }
              });
            };
            Emu.Kill(false, op->save);
          });
        }, nullptr, false);
        return true;
    }
}

namespace save_states
{
    bool busy() { return !!active_operation(); }

    bool validate_slot_file(const std::string& path)
    {
        try { return !owned_slot_path(path) || verified_payload(path); }
        catch (const std::exception& error)
        {
            save_state_log.error("Cannot verify save state integrity: %s", error.what());
            return false;
        }
    }

    void stopped()
    {
        const auto op = active_operation();
        // Snapshot the phase before the first stop's callback starts restoration.
        // The core may be waiting for this notification while loading holds its lock.
        if (!op || !op->awaiting_run.load()) return;
        Emu.CallFromMainThread([op]
        {
            run_operation(op, [op] { finish(op, false); });
        }, nullptr, false);
    }

    void running()
    {
        const auto identifier = static_cast<u64>(Emu.GetEmulationIdentifier());
        Emu.CallFromMainThread([identifier]
        {
            std::lock_guard lock(g_emu_lifecycle_mutex);
            const auto op = pending;
            if (!op || !op->awaiting_run || op->resume_identifier != identifier) return;
            finish(op, op->captured);
        }, nullptr, false);
    }

    bool defer_stop()
    {
        std::lock_guard lock(stop_mutex);
        if (!stoppable) return false;
        stoppable->stop_requested = true;
        return true;
    }

    void completed(bool success, bool game_stopped, const std::string& path)
    {
        std::lock_guard lock(g_emu_lifecycle_mutex);
        const auto op = pending;
        if (!op || !op->save) return;
        op->written = success;
        op->path = path;
        if (!game_stopped)
        {
            // The prepare thread's failure guard must release its callback first.
            Emu.CallFromMainThread([op]
            {
                std::lock_guard lock(g_emu_lifecycle_mutex);
                if (pending != op) return;
                Emu.SetContinuousMode(false);
                finish(op, false);
            }, nullptr, false);
        }
    }
}

extern "C" bool _rpcsx_requestSaveState(unsigned slot, long progress_id) { return request(slot, progress_id, true); }
extern "C" bool _rpcsx_saveStateBusy() { return save_states::busy(); }
extern "C" bool _rpcsx_requestLoadState(unsigned slot, long progress_id) { return request(slot, progress_id, false); }
extern "C" bool _rpcsx_saveStateToSlot(unsigned slot) { return request(slot, 0, true); }
extern "C" bool _rpcsx_loadStateFromSlot(unsigned slot) { return request(slot, 0, false); }

extern "C" bool _rpcsx_hasStateInSlot(unsigned slot)
{
    std::lock_guard lock(g_emu_lifecycle_mutex);
    return !find_slot(Emu.GetTitleID(), slot).empty();
}

extern "C" std::string _rpcsx_getSaveStateSlots()
{
    std::string title;
    {
        std::lock_guard lock(g_emu_lifecycle_mutex);
        title = pending ? pending->title : Emu.GetTitleID();
    }
    std::string result = "[";
    for (unsigned slot = 0; slot < slot_count; ++slot)
    {
        const auto path = find_slot(title, slot);
        fs::stat_t stat{};
        if (path.empty() || !fs::get_stat(path, stat)) continue;
        if (result.size() > 1) result += ',';
        result += "{\"index\":" + std::to_string(slot) + ",\"modified\":" + std::to_string(stat.mtime) +
            ",\"size\":" + std::to_string(stat.size) + "}";
    }
    return result + "]";
}
