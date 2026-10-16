package com.sbro.emucorec.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Seeds the upstream RPCS3 per-title configuration database.
 *
 * Desktop RPCS3 downloads `https://api.rpcs3.net/config/?api=v1` and applies
 * per-title recommended settings automatically. The core on Android reads the
 * same data from `<ps3>/config/config_db/<TITLE_ID>.yml` (see
 * `get_database_config` in `android/src/rpcsx-android.cpp`), but nothing
 * populates that directory, so titles that are broken under the generic
 * defaults (for example Assassin's Creed IV: Black Flag, which needs the PPU
 * static interpreter) would silently boot misconfigured.
 *
 * This seeder splits the snapshot bundled in assets (`rpcs3_config_db.json`,
 * refreshable with `./gradlew :app:updateConfigDb`) into that layout on first
 * run and whenever the bundled snapshot becomes newer. Failures are
 * non-fatal: the emulator simply boots without database configs.
 */
object ConfigDatabaseSeeder {
    private const val TAG = "EmuCoreC-ConfigDb"
    private const val ASSET_NAME = "rpcs3_config_db.json"
    private const val VERSION_NAME = ".version"

    /** Guards against pathological entries; real configs are a few hundred bytes. */
    private const val MAX_CONFIG_BYTES = 8192
    private val TITLE_ID_PATTERN = Regex("^[A-Z0-9]{9}$")

    fun ensureSeeded(context: Context) {
        runCatching { seed(context) }.onFailure { error ->
            Log.w(TAG, "Config database seeding failed, continuing without it", error)
        }
    }

    private fun seed(context: Context) {
        val raw = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: run {
            Log.w(TAG, "Bundled config database is not valid JSON")
            return
        }
        if (root.optInt("return_code", -255) != 0) {
            Log.w(TAG, "Bundled config database has bad return_code")
            return
        }
        val bundledTimestamp = root.optLong("timestamp", 0L).takeIf { it > 0 } ?: return
        val targetDir = File(EmulatorStorage.ps3Root(context), "config/config_db")

        if (readSeededTimestamp(targetDir) == bundledTimestamp && hasEntries(targetDir)) {
            return
        }

        val games = root.optJSONObject("games") ?: return
        // Wipe first so titles removed upstream cannot linger with stale configs.
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        var written = 0
        games.keys().forEach { titleId ->
            if (!TITLE_ID_PATTERN.matches(titleId)) return@forEach
            val config = games.optJSONObject(titleId)?.optString("config").orEmpty()
            if (config.isBlank() || config.toByteArray().size > MAX_CONFIG_BYTES) return@forEach
            runCatching {
                File(targetDir, "$titleId.yml").writeText(config)
                written++
            }
        }
        File(targetDir, VERSION_NAME).writeText(bundledTimestamp.toString())
        Log.i(TAG, "Seeded $written per-title configs (db timestamp $bundledTimestamp)")
    }

    private fun hasEntries(dir: File): Boolean =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".yml") }?.isNotEmpty() == true

    private fun readSeededTimestamp(dir: File): Long? =
        runCatching { File(dir, VERSION_NAME).readText().trim().toLong() }.getOrNull()
}
