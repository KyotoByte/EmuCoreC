package com.sbro.emucorec.ui.library

import com.sbro.emucorec.data.CustomizationSettings
import kotlin.math.floor

object LibraryGridSizing {
    private const val HORIZONTAL_CONTENT_PADDING_DP = 32f
    private const val CARD_SPACING_DP = 12f
    // 112dp kept three columns on a phone; 118dp keeps that layout while the
    // now full-bleed cards render noticeably larger artwork than the old
    // framed tiles with a title block ever did.
    private const val BASE_CARD_WIDTH_DP = 118f

    fun columnsForWidth(containerWidthDp: Float, coverSizePercent: Int): Int {
        val scale = coverSizePercent.coerceIn(
            CustomizationSettings.MIN_COVER_SIZE_PERCENT,
            CustomizationSettings.MAX_COVER_SIZE_PERCENT
        ) / 100f
        val availableWidth = (containerWidthDp - HORIZONTAL_CONTENT_PADDING_DP).coerceAtLeast(1f)
        val cardWidth = BASE_CARD_WIDTH_DP * scale
        return floor((availableWidth + CARD_SPACING_DP) / (cardWidth + CARD_SPACING_DP))
            .toInt()
            .coerceIn(1, 10)
    }
}
