package com.sbro.emucorec.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import com.sbro.emucorec.core.Ps3Core
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PatchRepository(private val context: Context) {

    sealed class DownloadResult {
        data object UpToDate : DownloadResult()
        data class Success(val patchesImported: Int) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    suspend fun downloadPatches(): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val version = Ps3Core.instance.patchEngineVersion()
            val url = "https://rpcs3.net/compatibility?patch&api=v1&v=$version"

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "EmuCoreC/1.0")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    return@withContext DownloadResult.Error("HTTP ${connection.responseCode}")
                }
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(json)
                val returnCode = root.optInt("return_code", -255)

                when {
                    returnCode == 1 -> DownloadResult.UpToDate
                    returnCode < 0 -> DownloadResult.Error("Server error: $returnCode")
                    else -> {
                        val patchContent = root.optString("patch", "")
                        if (patchContent.isEmpty()) {
                            DownloadResult.Error("Empty patch data")
                        } else {
                            val imported = Ps3Core.instance.patchesImport(patchContent)
                            if (imported < 0) {
                                DownloadResult.Error("Failed to import patches")
                            } else {
                                DownloadResult.Success(imported)
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            DownloadResult.Error(e.message ?: "Unknown error")
        }
    }

    fun listPatches(serial: String): List<Ps3PatchInfo> {
        return try {
            PatchListParser.parse(Ps3Core.instance.patchesList(serial))
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun togglePatch(
        hash: String,
        name: String,
        serial: String,
        appVersion: String,
        enabled: Boolean
    ): Boolean {
        return try {
            Ps3Core.instance.patchSetEnabled(hash, name, serial, appVersion, enabled)
        } catch (e: Exception) {
            false
        }
    }
}

internal object PatchListParser {
    private val parser = Json { ignoreUnknownKeys = true }

    fun parse(json: String): List<Ps3PatchInfo> {
        val array = parser.parseToJsonElement(json) as? JsonArray ?: return emptyList()
        return buildList(array.size) {
            for (element in array) {
                val obj = element as? JsonObject ?: continue
                val hash = obj.string("hash").trim()
                val name = obj.string("name").trim()
                if (hash.isEmpty() || name.isEmpty()) continue
                add(
                    Ps3PatchInfo(
                        hash = hash,
                        name = name,
                        author = obj.string("author"),
                        notes = obj.string("notes"),
                        version = obj.string("version"),
                        appVersion = obj.string("appVersion", "all").ifBlank { "all" },
                        game = obj.string("game"),
                        enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    )
                )
            }
        }.distinctBy(Ps3PatchInfo::identityKey)
    }

    private fun JsonObject.string(key: String, default: String = ""): String {
        return (this[key] as? JsonPrimitive)?.content ?: default
    }
}
