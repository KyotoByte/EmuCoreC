package com.sbro.emucorec.core

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONObject

/**
 * KyotoByte Edition: one-tap emulation presets.
 *
 *  - [Preset.ACCURATE]    = the original EmuCoreC defaults (the core's own default for each
 *                           key below, or EmuCoreC's [Ps3CoreSettingOverrides.RECOMMENDED_DEFAULTS]).
 *  - [Preset.PERFORMANCE] = speed-first values for mid-range phones.
 *
 * Every value here is an ordinary RPCS3 setting that can also be changed one by one in the
 * settings screen; the preset only writes them as global overrides (per-game profiles still
 * win). Nothing in this table has been benchmarked on a device by the KyotoByte fork: these are
 * the usual RPCS3 speed-over-accuracy trade-offs, and any game that breaks should be switched
 * back to "Accurate" (globally or in its per-game profile).
 */
object Ps3CorePerformancePresets {
    enum class Preset { ACCURATE, PERFORMANCE }

    private const val PREFS = "emucorec_kyotobyte_presets"
    private const val KEY_PRESET = "preset"
    private const val TAG = "EmuCoreC-Presets"

    /** Bumped after a preset is applied so open settings lists reload their values. */
    val revision = mutableIntStateOf(0)

    val PERFORMANCE: Map<String, String> = linkedMapOf(
        // Bigger SPU compilation blocks: fewer block transitions, faster in most games.
        // ("Safe" is RPCS3's most compatible choice.)
        "Core@@SPU Block Size" to "\"Mega\"",
        // Cheaper SPU float emulation than the default "Approximate".
        "Core@@SPU XFloat Accuracy" to "\"Relaxed\"",
        // Yield SPU threads that spin in wait loops, freeing phone cores for the PPU/RSX.
        "Core@@SPU loop detection" to "true",
        // Render below 720p internally: less GPU fill-rate on mobile GPUs.
        "Video@@Resolution Scale" to "75",
        // Lower-precision shader math is much cheaper on mobile GPUs (fp16 friendly).
        "Video@@Shader Precision" to "\"Low\"",
        // Offload RSX command processing to its own thread (phones have spare cores).
        "Video@@Multithreaded RSX" to "true",
        // Fewer GPU<->CPU syncs for occlusion queries.
        "Video@@Relaxed ZCULL Sync" to "true",
        "Video@@Accurate ZCULL stats" to "false",
    )

    fun current(context: Context): Preset =
        prefs(context).getString(KEY_PRESET, null)
            ?.let { runCatching { Preset.valueOf(it) }.getOrNull() }
            ?: Preset.ACCURATE

    /** Applies [preset] to the live core and records it as global overrides. */
    fun apply(context: Context, preset: Preset): Boolean {
        if (!Ps3Runtime.ensureInitialized(context)) return false
        var ok = true
        run {
            when (preset) {
                Preset.PERFORMANCE -> PERFORMANCE.forEach { (path, value) ->
                    if (set(path, value)) {
                        Ps3CoreSettingOverrides.recordGlobal(context, path, value)
                    } else {
                        ok = false
                    }
                }
                Preset.ACCURATE -> PERFORMANCE.keys.forEach { path ->
                    val original = Ps3CoreSettingOverrides.RECOMMENDED_DEFAULTS[path]
                        ?: coreDefault(path)
                    Ps3CoreSettingOverrides.clearGlobal(context, path)
                    if (original == null || !set(path, original)) ok = false
                }
            }
        }
        CoreSettingsTreeCache.invalidateTree()
        prefs(context).edit().putString(KEY_PRESET, preset.name).apply()
        revision.intValue++
        return ok
    }

    private fun set(path: String, value: String): Boolean {
        val applied = runCatching { Ps3Core.instance.settingsSet(path, value) }.getOrDefault(false)
        if (!applied) Log.w(TAG, "RPCS3 rejected preset value $path = $value")
        return applied
    }

    /** The default this core build reports for one leaf, JSON-encoded like settingsSet wants. */
    private fun coreDefault(path: String): String? = runCatching {
        val node = JSONObject(Ps3Core.instance.settingsGet(path))
        when (node.optString("type")) {
            "bool" -> node.optBoolean("default").toString()
            "int", "uint", "float" -> node.optString("default")
            "" -> null
            else -> JSONObject.quote(node.optString("default"))
        }
    }.getOrNull()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
