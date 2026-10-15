package com.sbro.emucorec.ui.emulation

import kotlin.math.min

internal const val GROUP_SCALE_STEP_PERCENT = 10
internal const val GROUP_SCALE_MIN_PERCENT = 50
internal const val GROUP_SCALE_MAX_PERCENT = 250

/**
 * Scales every control of a selected group around the group's visual centre so the whole
 * block keeps its relative layout while all buttons change size together.
 */
internal fun List<TouchControlElement>.scaleGroupAroundCenter(factor: Float): List<TouchControlElement> {
    if (size < 2 || !factor.isFinite() || factor <= 0f) return this
    if (any { !it.x.isFinite() || !it.y.isFinite() || !it.width.isFinite() ||
            !it.height.isFinite() || it.width <= 0f || it.height <= 0f }) return this

    val left = minOf { it.x }
    val top = minOf { it.y }
    val right = maxOf { it.x + it.width }
    val bottom = maxOf { it.y + it.height }
    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f
    val maximumFactor = minOf(
        1f / (right - left),
        1f / (bottom - top),
        minOf { 0.5f / it.width },
        minOf { 0.5f / it.height }
    )
    val appliedFactor = min(factor, maximumFactor)
    val scaledLeft = centerX + (left - centerX) * appliedFactor
    val scaledTop = centerY + (top - centerY) * appliedFactor
    val scaledRight = centerX + (right - centerX) * appliedFactor
    val scaledBottom = centerY + (bottom - centerY) * appliedFactor
    val shiftX = when {
        scaledLeft < 0f -> -scaledLeft
        scaledRight > 1f -> 1f - scaledRight
        else -> 0f
    }
    val shiftY = when {
        scaledTop < 0f -> -scaledTop
        scaledBottom > 1f -> 1f - scaledBottom
        else -> 0f
    }

    return map { element ->
        element.copy(
            x = centerX + (element.x - centerX) * appliedFactor + shiftX,
            y = centerY + (element.y - centerY) * appliedFactor + shiftY,
            width = element.width * appliedFactor,
            height = element.height * appliedFactor
        )
    }
}
