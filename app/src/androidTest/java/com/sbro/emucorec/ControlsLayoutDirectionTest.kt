package com.sbro.emucorec

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import com.sbro.emucorec.data.TouchControlPressEffect
import com.sbro.emucorec.data.TouchControlVisualStyle
import com.sbro.emucorec.ui.emulation.OnScreenControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ControlsLayoutDirectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingAppLanguageDirectionPreservesPhysicalControllerGeometry() {
        val direction = mutableStateOf(LayoutDirection.Ltr)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                    OnScreenControls(
                        overlayScale = 0.9f, overlayOpacity = 100,
                        visualStyle = TouchControlVisualStyle.CLASSIC,
                        pressEffect = TouchControlPressEffect.GROW,
                        touchHaptics = false, touchHapticsPreset = 0, touchHapticsStrength = 100,
                        editMode = false, savedLayout = null, onLayoutChange = {},
                        onEditDone = {}, onEditReset = {}, onButtonChange = { _, _ -> }, onAxisChange = { _, _ -> }
                    )
                }
            }
        }
        val ids = listOf("dpad_up", "dpad_down", "left_stick", "right_stick", "cross", "circle", "l1", "r1")
        fun bounds() = ids.associateWith { compose.onNodeWithTag("touch_control_$it", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
        val before = bounds()
        compose.runOnIdle { direction.value = LayoutDirection.Rtl }
        val after = bounds()
        assertEquals("Persian/Arabic must not mirror the physical DualShock layout", before, after)
        assertTrue(after.getValue("dpad_up").center.x < after.getValue("cross").center.x)
        assertTrue(after.getValue("left_stick").center.x < after.getValue("right_stick").center.x)
    }
}
