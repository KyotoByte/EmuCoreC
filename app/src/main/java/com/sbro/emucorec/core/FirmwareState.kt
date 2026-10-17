package com.sbro.emucorec.core

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

enum class FirmwareStatus {
    None,
    Installed,
    Compiled
}

@Serializable
private data class FirmwareInfo(val version: String?, val status: FirmwareStatus)

/**
 * Process-wide firmware state, persisted to <root>/fw.json.
 *
 * Installed means the PUP contents are on disk (dev_flash populated); Compiled
 * additionally means the VSH PPU cache has been built. The two are separate
 * because installation must not wait for compilation -- see
 * Ps3InstallBridge.installFirmware.
 */
object FirmwareState {
    private val lock = Any()

    val version: MutableState<String?> = mutableStateOf(null)
    val status: MutableState<FirmwareStatus> = mutableStateOf(FirmwareStatus.None)

    fun save() {
        synchronized(lock) {
            try {
                File(Ps3Core.rootDirectory + "fw.json").writeText(
                    Json.encodeToString(FirmwareInfo(version.value, status.value))
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun load() {
        synchronized(lock) {
            // No fw.json is the normal first-run state, not an error: reading it
            // blind threw FileNotFoundException and its stack trace reads as a
            // crash in the diagnostic log, where it was reported as one.
            val file = File(Ps3Core.rootDirectory + "fw.json")
            if (!file.isFile) return
            try {
                val info = Json.decodeFromString<FirmwareInfo>(file.readText())
                status.value = info.status
                version.value = info.version
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun onInstalled(version: String?) = updateStatus(version, FirmwareStatus.Installed)

    fun onCompiled(version: String?) = updateStatus(version, FirmwareStatus.Compiled)

    fun updateStatus(version: String?, status: FirmwareStatus) {
        synchronized(lock) {
            this.version.value = version
            this.status.value = status
            save()
        }
    }
}
