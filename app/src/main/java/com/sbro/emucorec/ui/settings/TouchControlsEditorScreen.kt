package com.sbro.emucorec.ui.settings

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sbro.emucorec.core.Ps3CoreConfigRepository
import com.sbro.emucorec.core.Ps3GameSettingsRepository
import com.sbro.emucorec.ui.theme.LocalCustomizationSettings
import com.sbro.emucorec.ui.emulation.OnScreenControls
import com.sbro.emucorec.ui.emulation.TouchControlLayoutRepository

/**
 * Standalone touch control layout editor.
 *
 * With a null/blank titleId it edits the global default layout. With a titleId
 * it edits that game's custom layout. Per-game layouts are never overwritten
 * by global edits: they are loaded in place of the global one while playing.
 */
@Composable
fun TouchControlsEditorScreen(
    onExit: () -> Unit,
    titleId: String? = null
) {
    val context = LocalContext.current
    val configRepository = remember(context) { Ps3CoreConfigRepository(context) }
    val gameSettingsRepository = remember(context) { Ps3GameSettingsRepository(context) }
    var config by remember(configRepository, gameSettingsRepository, titleId) {
        mutableStateOf(
            if (titleId.isNullOrBlank()) configRepository.ensureDefaultsPersisted()
            else gameSettingsRepository.loadEffective(titleId)
        )
    }
    val customization = LocalCustomizationSettings.current
    val layoutRepository = remember(context) { TouchControlLayoutRepository(context) }
    var layout by remember(layoutRepository, titleId) { mutableStateOf(layoutRepository.load(titleId)) }
    var editorRevision by remember(titleId) { mutableIntStateOf(0) }

    // The touch controls only exist in landscape (the game is landscape-only);
    // lock the editor to landscape and release the lock when leaving it.
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (activity != null && previousOrientation != null) {
                activity.requestedOrientation = previousOrientation
            }
        }
    }

    BackHandler(onBack = onExit)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        key(titleId, editorRevision) {
            OnScreenControls(
                overlayScale = config.overlayScale,
                overlayOpacity = config.overlayOpacity,
                visualStyle = customization.touchControlVisualStyle,
                pressEffect = customization.touchControlPressEffect,
                touchHaptics = false,
                touchHapticsPreset = config.touchHapticsPreset,
                touchHapticsStrength = config.touchHapticsStrength,
                editMode = true,
                savedLayout = layout,
                onLayoutChange = { updated ->
                    layout = updated
                    layoutRepository.save(titleId, updated)
                },
                onEditDone = onExit,
                onEditReset = {
                    layoutRepository.reset(titleId)
                    val resetConfig = config.copy(overlayScale = 0.9f, overlayOpacity = 100)
                    if (titleId.isNullOrBlank()) configRepository.save(resetConfig)
                    else gameSettingsRepository.savePreservingDriverOverride(titleId, resetConfig)
                    config = resetConfig
                    layout = layoutRepository.load(titleId)
                    editorRevision++
                },
                onButtonChange = { _, _ -> },
                onAxisChange = { _, _ -> }
            )
        }
    }
}
