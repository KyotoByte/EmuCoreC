package com.sbro.emucorec.core.ps3.overlay

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sbro.emucorec.core.Digital1Flags
import com.sbro.emucorec.core.Digital2Flags
import com.sbro.emucorec.core.Ps3Core
import com.sbro.emucorec.core.Ps3CoreConfig
import com.sbro.emucorec.core.Ps3GameSettingsRepository
import com.sbro.emucorec.core.ps3.Emulator
import kotlin.math.abs
import kotlin.math.roundToInt

/** Translates EmuCoreC's per-control overlay events to complete RPCS3 pad snapshots. */
class InputOverlay(context: Context) {
    private val emulator = context as? Emulator
    private val repository = Ps3GameSettingsRepository(context)
    private var latestConfig = repository.loadEffective(emulator?.currentGameIdOrIntent().orEmpty())
    private var touchControlsRuntimeActive by mutableStateOf(true)
    private var physicalControllerPresent = false
    // Set the first time ANY physical gamepad event arrives. The classifier-based
    // controller presence probe is only a hint for the UI; a pad it fails to
    // recognise (or one connected mid-session without an InputManager callback)
    // must not leave input dead. The pad is pushed unconditionally from the
    // activity; this mirrors that, gated only on the emulator being initialised.
    private var physicalInputSeen = false
    private var pushFailureLogged = false
    private val lock = Any()

    private var attached = false
    private var digital1 = 0
    private var digital2 = 0
    private var leftX = STICK_CENTER
    private var leftY = STICK_CENTER
    private var rightX = STICK_CENTER
    private var rightY = STICK_CENTER

    var hasReceivedCoreState by mutableStateOf(false)
        private set
    var coreOverlayMask by mutableIntStateOf(0)
        private set
    var overlayEditMode by mutableStateOf(false)
        private set
    var overlayScale by mutableFloatStateOf(latestConfig.overlayScale)
        private set
    var overlayOpacity by mutableIntStateOf(latestConfig.overlayOpacity.coerceIn(10, 100))
        private set

    val effectiveOverlayMask: Int
        get() = if (touchControlsRuntimeActive && latestConfig.enableGamepadOverlay) {
            buildDisplayMask(latestConfig)
        } else {
            0
        }

    fun synchronizeConfig(config: Ps3CoreConfig) {
        latestConfig = config
        overlayScale = config.overlayScale
        overlayOpacity = config.overlayOpacity.coerceIn(10, 100)
        syncControllerAttachment()
    }

    fun setState(overlayMask: Int) {
        hasReceivedCoreState = true
        coreOverlayMask = overlayMask
        syncControllerAttachment()
    }

    fun ensureControllerAttached(): Boolean {
        syncControllerAttachment()
        return attached
    }

    fun setTouchControlsActive(active: Boolean) {
        touchControlsRuntimeActive = active
        syncControllerAttachment()
    }

    fun setPhysicalControllerPresent(present: Boolean) {
        physicalControllerPresent = present
        syncControllerAttachment()
    }

    fun setIsInEditMode(edit: Boolean) {
        overlayEditMode = edit
        if (edit) emulator?.requestOverlayMenuButtonReveal()
    }

    fun resetButtonPlacement() = persistConfig(
        latestConfig.copy(overlayScale = DEFAULT_OVERLAY_SCALE, overlayOpacity = DEFAULT_OVERLAY_OPACITY)
    )

    fun setScale(scale: Float) = persistConfig(latestConfig.copy(overlayScale = scale))

    fun setOpacity(opacity: Int) =
        persistConfig(latestConfig.copy(overlayOpacity = opacity.coerceIn(10, 100)))

    fun attachController(): Boolean = synchronized(lock) {
        attached = true
        pushLocked()
    }

    fun detachController() = synchronized(lock) {
        digital1 = 0
        digital2 = 0
        leftX = STICK_CENTER
        leftY = STICK_CENTER
        rightX = STICK_CENTER
        rightY = STICK_CENTER
        if (attached) pushLocked()
        attached = false
    }

    fun setAxis(axis: Int, value: Short) = synchronized(lock) {
        val converted = (((value.toInt() - Short.MIN_VALUE.toInt()) * 255L + 32767L) / 65535L)
            .toInt().coerceIn(0, 255)
        when (axis) {
            ControlId.axis_left_x -> leftX = converted
            ControlId.axis_left_y -> leftY = converted
            ControlId.axis_right_x -> rightX = converted
            ControlId.axis_right_y -> rightY = converted
            else -> return@synchronized
        }
        pushLocked()
    }

    /** Applies the per-game physical-pad profile before sending an axis to RPCS3. */
    fun setPhysicalAxis(axis: Int, value: Short) {
        physicalInputSeen = true
        syncControllerAttachment()
        val target = when {
            !latestConfig.gamepadSwapSticks -> axis
            axis == ControlId.axis_left_x -> ControlId.axis_right_x
            axis == ControlId.axis_left_y -> ControlId.axis_right_y
            axis == ControlId.axis_right_x -> ControlId.axis_left_x
            axis == ControlId.axis_right_y -> ControlId.axis_left_y
            else -> axis
        }
        val inverted = when (target) {
            ControlId.axis_left_x -> latestConfig.gamepadInvertLeftX
            ControlId.axis_left_y -> latestConfig.gamepadInvertLeftY
            ControlId.axis_right_x -> latestConfig.gamepadInvertRightX
            ControlId.axis_right_y -> latestConfig.gamepadInvertRightY
            else -> false
        }
        val normalized = (value.toInt() / Short.MAX_VALUE.toFloat()).coerceIn(-1f, 1f)
        val deadzone = latestConfig.gamepadDeadzone.coerceIn(0f, 0.95f)
        val outsideDeadzone = if (abs(normalized) <= deadzone) {
            0f
        } else {
            val magnitude = ((abs(normalized) - deadzone) / (1f - deadzone))
                .times(latestConfig.analogMultiplier)
                .coerceIn(0f, 1f)
            if (normalized < 0f) -magnitude else magnitude
        }
        val transformed = if (inverted) -outsideDeadzone else outsideDeadzone
        setAxis(
            target,
            (transformed * Short.MAX_VALUE)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort(),
        )
    }

    fun setPhysicalTrigger(button: Int, value: Float) {
        physicalInputSeen = true
        syncControllerAttachment()
        setButton(button, value.coerceIn(0f, 1f) > latestConfig.gamepadTriggerThreshold)
    }

    fun setPhysicalButton(button: Int, value: Boolean) {
        physicalInputSeen = true
        syncControllerAttachment()
        val mapped = when (latestConfig.gamepadButtonProfile) {
            Ps3CoreConfig.GAMEPAD_PROFILE_SWAP_CROSS_CIRCLE -> when (button) {
                ControlId.a -> ControlId.b
                ControlId.b -> ControlId.a
                else -> button
            }
            Ps3CoreConfig.GAMEPAD_PROFILE_NINTENDO_FACE -> when (button) {
                ControlId.a -> ControlId.b
                ControlId.b -> ControlId.a
                ControlId.x -> ControlId.y
                ControlId.y -> ControlId.x
                else -> button
            }
            else -> button
        }
        setButton(mapped, value)
    }

    fun setButton(button: Int, value: Boolean) = synchronized(lock) {
        val d1 = when (button) {
            ControlId.select -> Digital1Flags.CELL_PAD_CTRL_SELECT.bit
            ControlId.l3 -> Digital1Flags.CELL_PAD_CTRL_L3.bit
            ControlId.r3 -> Digital1Flags.CELL_PAD_CTRL_R3.bit
            ControlId.guide -> Digital1Flags.CELL_PAD_CTRL_PS.bit
            ControlId.start -> Digital1Flags.CELL_PAD_CTRL_START.bit
            ControlId.dup -> Digital1Flags.CELL_PAD_CTRL_UP.bit
            ControlId.ddown -> Digital1Flags.CELL_PAD_CTRL_DOWN.bit
            ControlId.dleft -> Digital1Flags.CELL_PAD_CTRL_LEFT.bit
            ControlId.dright -> Digital1Flags.CELL_PAD_CTRL_RIGHT.bit
            else -> 0
        }
        val d2 = when (button) {
            ControlId.a -> Digital2Flags.CELL_PAD_CTRL_CROSS.bit
            ControlId.b -> Digital2Flags.CELL_PAD_CTRL_CIRCLE.bit
            ControlId.x -> Digital2Flags.CELL_PAD_CTRL_SQUARE.bit
            ControlId.y -> Digital2Flags.CELL_PAD_CTRL_TRIANGLE.bit
            ControlId.l1 -> Digital2Flags.CELL_PAD_CTRL_L1.bit
            ControlId.r1 -> Digital2Flags.CELL_PAD_CTRL_R1.bit
            ControlId.l2 -> Digital2Flags.CELL_PAD_CTRL_L2.bit
            ControlId.r2 -> Digital2Flags.CELL_PAD_CTRL_R2.bit
            else -> 0
        }
        digital1 = digital1.withBit(d1, value)
        digital2 = digital2.withBit(d2, value)
        pushLocked()
    }

    fun dispose() = detachController()

    private fun persistConfig(config: Ps3CoreConfig) {
        latestConfig = config
        overlayScale = config.overlayScale
        overlayOpacity = config.overlayOpacity.coerceIn(10, 100)
        repository.savePreservingDriverOverride(emulator?.currentGameIdOrIntent().orEmpty(), config)
        syncControllerAttachment()
    }

    private fun syncControllerAttachment() {
        if (physicalControllerPresent || physicalInputSeen || effectiveOverlayMask != 0) {
            attachController()
        } else {
            detachController()
        }
    }

    private fun buildDisplayMask(config: Ps3CoreConfig): Int {
        return OVERLAY_MASK_BASIC or OVERLAY_MASK_L2R2
    }

    private fun pushLocked(): Boolean {
        if (!attached || !Ps3Core.initialized) return false
        val pushed = runCatching {
            Ps3Core.instance.overlayPadData(digital1, digital2, leftX, leftY, rightX, rightY)
        }.getOrDefault(false)
        if (!pushed && !pushFailureLogged) {
            pushFailureLogged = true
            Log.w(
                "InputOverlay",
                "overlayPadData rejected by the core: the virtual pad for this port was not created. " +
                    "Check the core log for 'Pad 0 ...' and the player 1 pad handler in config."
            )
        }
        return pushed
    }

    private fun Int.withBit(bit: Int, enabled: Boolean): Int = when {
        bit == 0 -> this
        enabled -> this or bit
        else -> this and bit.inv()
    }

    companion object {
        const val OVERLAY_MASK_BASIC = 1
        const val OVERLAY_MASK_L2R2 = 2
        private const val STICK_CENTER = 128
        private const val DEFAULT_OVERLAY_SCALE = 0.9f
        private const val DEFAULT_OVERLAY_OPACITY = 100
    }

    object ControlId {
        const val a = 0
        const val b = 1
        const val x = 2
        const val y = 3
        const val select = 4
        const val guide = 5
        const val start = 6
        const val l1 = 9
        const val r1 = 10
        const val dup = 11
        const val ddown = 12
        const val dleft = 13
        const val dright = 14
        const val l2 = -4
        const val r2 = -5
        const val l3 = -6
        const val r3 = -7
        const val touch = 1024
        const val axis_left_x = 0
        const val axis_left_y = 1
        const val axis_right_x = 2
        const val axis_right_y = 3
    }
}
