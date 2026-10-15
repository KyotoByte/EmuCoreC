package com.sbro.emucorec.ui.emulation

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class TouchControlElement(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val visible: Boolean = true,
    val analogMode: TouchAnalogMode = TouchAnalogMode.Stick,
    val actionId: String = id,
    val secondaryActionId: String? = null,
    val opacity: Int = CONTROL_OPACITY_MAX
)

internal const val CONTROL_OPACITY_MIN = 10
internal const val CONTROL_OPACITY_MAX = 100
internal const val CONTROL_OPACITY_STEP = 10

/** Normalized coordinates must preserve positive sizes independently of canvas resolution. */
internal fun normalizedTouchControlDimension(value: Float): Float =
    value.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(0.5f) ?: 0.08f

internal fun TouchControlElement.normalized(): TouchControlElement {
    val safeWidth = normalizedTouchControlDimension(width)
    val safeHeight = normalizedTouchControlDimension(height)
    return copy(
        x = x.takeIf(Float::isFinite)?.coerceIn(0f, 1f - safeWidth) ?: 0.5f,
        y = y.takeIf(Float::isFinite)?.coerceIn(0f, 1f - safeHeight) ?: 0.5f,
        width = safeWidth, height = safeHeight,
        secondaryActionId = secondaryActionId?.takeIf { it in TouchControlIds.BUTTON_IDS && it != actionId },
        opacity = opacity.coerceIn(CONTROL_OPACITY_MIN, CONTROL_OPACITY_MAX)
    )
}

enum class TouchAnalogMode(val storageValue: String) {
    Stick("stick"),
    TouchArea("touch_area");

    companion object {
        fun fromStorage(value: String?): TouchAnalogMode {
            return entries.firstOrNull { it.storageValue == value } ?: Stick
        }
    }
}

class TouchControlLayoutRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Loads the layout for a game: a custom per-game layout when one exists,
     * otherwise the global default. Null or blank gameId reads the global default.
     */
    fun load(gameId: String? = null): List<TouchControlElement>? {
        val key = gameId?.takeIf(String::isNotBlank)?.let(::perGameKey)
        val raw = key?.let { preferences.getString(it, null) }
            ?: preferences.getString(KEY_LAYOUT, null)
            ?: return null
        return parse(raw)
    }

    /** True when the game has its own custom layout saved. */
    fun hasCustomLayout(gameId: String): Boolean =
        gameId.isNotBlank() && preferences.contains(perGameKey(gameId))

    /** Saves a per-game layout when gameId is provided, the global default otherwise. */
    fun save(gameId: String? = null, elements: List<TouchControlElement>) {
        val array = JSONArray()
        elements.distinctBy { it.id }.map { it.normalized() }.forEach { element ->
            array.put(
                JSONObject()
                    .put("id", element.id)
                    .put("x", element.x)
                    .put("y", element.y)
                    .put("width", element.width)
                    .put("height", element.height)
                    .put("visible", element.visible)
                    .put("analogMode", element.analogMode.storageValue)
                    .put("actionId", element.actionId)
                    .put("secondaryActionId", element.secondaryActionId)
                    .put("opacity", element.opacity)
            )
        }
        val key = gameId?.takeIf(String::isNotBlank)?.let(::perGameKey) ?: KEY_LAYOUT
        preferences.edit { putString(key, array.toString()) }
    }

    /** Removes a per-game layout when gameId is provided, the global default otherwise. */
    fun reset(gameId: String? = null) {
        val key = gameId?.takeIf(String::isNotBlank)?.let(::perGameKey) ?: KEY_LAYOUT
        preferences.edit { remove(key) }
    }

    private fun parse(raw: String): List<TouchControlElement>? = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    TouchControlElement(
                        id = item.getString("id"),
                        x = item.getDouble("x").toFloat(),
                        y = item.getDouble("y").toFloat(),
                        width = item.getDouble("width").toFloat(),
                        height = item.getDouble("height").toFloat(),
                        visible = item.optBoolean("visible", true),
                        actionId = item.optString("actionId", item.getString("id")),
                        secondaryActionId = item.optString("secondaryActionId").takeIf { it.isNotBlank() },
                        opacity = item.optInt("opacity", CONTROL_OPACITY_MAX),
                        analogMode = TouchAnalogMode.fromStorage(
                            if (item.has("analogMode")) item.optString("analogMode") else null
                        )
                    ).coerceToCanvas()
                )
            }
        }
    }.getOrNull()

    private fun perGameKey(gameId: String): String {
        // The intent-carried and the core-reported title IDs can differ in
        // case/whitespace; normalize so save and load always hit the same key.
        val safe = gameId.trim().uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(64)
        return "layout_v2_game_$safe"
    }

    private fun TouchControlElement.coerceToCanvas(): TouchControlElement = normalized()

    private companion object {
        const val PREFS_NAME = "touch_control_layout"
        // Bumped when a default-layout change must reach devices that already
        // saved a stale copy of the previous default (e.g. L3's position).
        const val KEY_LAYOUT = "layout_v2"
    }
}

object TouchControlIds {
    const val L2 = "l2"
    const val L1 = "l1"
    const val R2 = "r2"
    const val R1 = "r1"
    const val DPAD_UP = "dpad_up"
    const val DPAD_DOWN = "dpad_down"
    const val DPAD_LEFT = "dpad_left"
    const val DPAD_RIGHT = "dpad_right"
    const val LEFT_STICK = "left_stick"
    const val RIGHT_STICK = "right_stick"
    const val L3 = "l3"
    const val R3 = "r3"
    const val TRIANGLE = "triangle"
    const val CROSS = "cross"
    const val SQUARE = "square"
    const val CIRCLE = "circle"
    const val SELECT = "select"
    const val START = "start"

    val BUTTON_IDS = setOf(L2, L1, R2, R1, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
        L3, R3, TRIANGLE, CROSS, SQUARE, CIRCLE, SELECT, START)
}
