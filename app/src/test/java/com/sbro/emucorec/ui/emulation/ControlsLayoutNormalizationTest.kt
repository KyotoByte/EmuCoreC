package com.sbro.emucorec.ui.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsLayoutNormalizationTest {
    @Test fun enlargedEdgeGroupMovesAsAUnitWithoutCreatingOverlap() {
        val left = TouchControlElement(TouchControlIds.SQUARE, 0.8f, 0.8f, 0.1f, 0.1f)
        val right = TouchControlElement(TouchControlIds.CIRCLE, 0.9f, 0.8f, 0.1f, 0.1f)
        val scaled = listOf(left, right).scaleGroupAroundCenter(2f)
        assertEquals(0.2f, scaled[0].width, 0.000001f)
        assertEquals(0.2f, scaled[1].x - scaled[0].x, 0.000001f)
        assertTrue(scaled[0].x + scaled[0].width <= scaled[1].x + 0.000001f)
        assertEquals(1f, scaled[1].x + scaled[1].width, 0.000001f)
    }

    @Test fun canvasFitCapsTheSameScaleForAllGroupMembers() {
        val left = TouchControlElement(TouchControlIds.SQUARE, 0.1f, 0.4f, 0.1f, 0.1f)
        val right = TouchControlElement(TouchControlIds.CIRCLE, 0.8f, 0.4f, 0.1f, 0.1f)
        val scaled = listOf(left, right).scaleGroupAroundCenter(2f)
        assertEquals(0.125f, scaled[0].width, 0.000001f)
        assertEquals(scaled[0].width, scaled[1].width, 0.000001f)
        assertEquals(0f, scaled[0].x, 0.000001f)
        assertEquals(1f, scaled[1].x + scaled[1].width, 0.000001f)
        assertEquals((right.x - left.x) * 1.25f, scaled[1].x - scaled[0].x, 0.000001f)
    }

    @Test fun maximumControlSizeCapsWholeGroupWithoutChangingAspectRatios() {
        val first = TouchControlElement(TouchControlIds.SQUARE, 0.1f, 0.2f, 0.4f, 0.1f)
        val second = TouchControlElement(TouchControlIds.CIRCLE, 0.6f, 0.2f, 0.1f, 0.1f)
        val scaled = listOf(first, second).scaleGroupAroundCenter(2f)
        assertEquals(0.5f, scaled[0].width, 0.000001f)
        assertEquals(0.125f, scaled[0].height, 0.000001f)
        assertEquals(0.125f, scaled[1].width, 0.000001f)
        assertEquals((second.x - first.x) * 1.25f, scaled[1].x - scaled[0].x, 0.000001f)
    }

    @Test fun smallValidGeometrySurvivesRepeatedSaveAndLoadNormalization() {
        val original = TouchControlElement(
            TouchControlIds.CROSS, 0.1f, 0.2f, 0.004f, 0.0064f,
            actionId = TouchControlIds.CROSS, secondaryActionId = TouchControlIds.L3, opacity = 40
        )
        var restored = original
        repeat(4) { restored = restored.normalized() }
        assertEquals(original, restored)
    }

    @Test fun shrinkingSmallGroupsPreservesTheirDimensionsAndSpacing() {
        val left = TouchControlElement(TouchControlIds.SQUARE, 0.3f, 0.4f, 0.008f, 0.0128f)
        val right = TouchControlElement(TouchControlIds.CIRCLE, 0.32f, 0.4f, 0.008f, 0.0128f)
        val scaled = listOf(left, right).scaleGroupAroundCenter(0.5f)
        assertEquals(0.004f, scaled[0].width, 0.000001f)
        assertEquals(0.0064f, scaled[0].height, 0.000001f)
        assertEquals((right.x - left.x) * 0.5f, scaled[1].x - scaled[0].x, 0.000001f)
    }

    @Test fun corruptGeometryCannotPutAControlOutsideTheCanvas() {
        val element = TouchControlElement(
            TouchControlIds.CROSS, Float.NaN, Float.POSITIVE_INFINITY,
            Float.NaN, Float.NEGATIVE_INFINITY, opacity = 200
        ).normalized()
        assertTrue(element.x.isFinite() && element.y.isFinite())
        assertTrue(element.width > 0f && element.height > 0f)
        assertTrue(element.x + element.width <= 1f && element.y + element.height <= 1f)
        assertEquals(100, element.opacity)
    }

    @Test fun combosAcceptPs3StickClicksAndRejectUnsupportedOrRepeatedActions() {
        val button = TouchControlElement(TouchControlIds.CROSS, 0.2f, 0.2f, 0.1f, 0.1f)
        assertEquals(TouchControlIds.L3, button.copy(secondaryActionId = TouchControlIds.L3).normalized().secondaryActionId)
        assertEquals(TouchControlIds.R3, button.copy(secondaryActionId = TouchControlIds.R3).normalized().secondaryActionId)
        assertNull(button.copy(secondaryActionId = "touch").normalized().secondaryActionId)
        assertNull(button.copy(secondaryActionId = TouchControlIds.CROSS).normalized().secondaryActionId)
    }

    @Test fun legacyElementsKeepTheirOriginalActionAndFullOpacity() {
        val legacy = TouchControlElement(TouchControlIds.L2, 0.1f, 0.1f, 0.1f, 0.1f).normalized()
        assertEquals(TouchControlIds.L2, legacy.actionId)
        assertNull(legacy.secondaryActionId)
        assertEquals(100, legacy.opacity)
        assertTrue(legacy.visible)
    }
}
