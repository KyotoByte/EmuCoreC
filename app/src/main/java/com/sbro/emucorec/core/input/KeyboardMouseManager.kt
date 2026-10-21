package com.sbro.emucorec.core.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.sbro.emucorec.data.AppPreferences
import com.sbro.emucorec.data.KeyboardMouseSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Receives fully resolved controller actions. EmuCoreC applies them as a separate input lane in
 * [com.sbro.emucorec.core.ps3.overlay.InputOverlay], so keyboard/mouse releases can never clear a
 * button that the touch overlay or a physical pad is still holding.
 */
interface KeyboardMousePadSink {
    fun onAction(source: Int, actionId: String, pressed: Boolean, intensity: Float)
    fun onMouseAim(stick: Int, x: Float, y: Float)
    fun releaseSource(source: Int)
}

/**
 * Owns keyboard/mouse event translation, settings, connection state and pointer-capture policy.
 * The core connection itself is intentionally abstracted behind [KeyboardMousePadSink].
 */
object KeyboardMouseManager {
    enum class BindingSource {
        KEYBOARD,
        MOUSE
    }

    data class ConnectionState(
        val keyboardConnected: Boolean = false,
        val mouseConnected: Boolean = false
    )

    data class BindingCapture(
        val source: BindingSource,
        val actionId: String
    )

    private data class HeldBinding(
        val actionId: String,
        val shortcut: Boolean
    )

    private const val TAG = "EmuCoreCInput"
    private const val CONNECTION_POLL_INTERVAL_MS = 500L
    private const val MOUSE_AIM_IDLE_RELEASE_MS = 70L

    private val FINGERPRINT_UINPUT_TOKENS = setOf(
        "uinput-fpc",
        "uinput-goodix",
        "uinput-synaptics",
        "uinput-elan",
        "uinput-vfs",
        "uinput-atrus"
    )

    private val INTERNAL_KEYBOARD_NAMES = setOf(
        "gpio-keys",
        "pmic_resin",
        "pmic_pwrkey",
        "uinput_nav",
        "oplus_fp_input"
    )

    private val SYSTEM_KEY_CODES = setOf(
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER
    )

    private val GAMEPAD_SOURCES =
        InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _globalSettings = MutableStateFlow(KeyboardMouseSettings())
    val globalSettings: StateFlow<KeyboardMouseSettings> = _globalSettings.asStateFlow()

    private val _gameOverride = MutableStateFlow<KeyboardMouseSettings?>(null)
    val gameOverride: StateFlow<KeyboardMouseSettings?> = _gameOverride.asStateFlow()

    private val _settings = MutableStateFlow(KeyboardMouseSettings())
    /** Effective settings: the active per-game override, or the global preferences. */
    val settings: StateFlow<KeyboardMouseSettings> = _settings.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _bindingCapture = MutableStateFlow<BindingCapture?>(null)
    val bindingCapture: StateFlow<BindingCapture?> = _bindingCapture.asStateFlow()

    private val _quickActions = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val quickActions: SharedFlow<String> = _quickActions.asSharedFlow()

    private var captureCallback: ((Int) -> Unit)? = null
    private var backCaptureUpPending = false

    @Volatile
    private var initialized = false

    @Volatile
    private var emulationInputEnabled = false

    @Volatile
    private var pointerCaptureRequested = false
    private var pointerCaptureSuppressed = false
    private var pointerCaptureListener: ((Boolean) -> Unit)? = null

    @Volatile
    private var sink: KeyboardMousePadSink? = null

    private val heldKeyboard = mutableMapOf<Int, HeldBinding>()
    private val heldMouse = mutableMapOf<Int, HeldBinding>()
    private var aimReleaseJob: Job? = null
    private val wheelReleaseJobs = mutableMapOf<String, Job>()

    private var lastAbsoluteX = Float.NaN
    private var lastAbsoluteY = Float.NaN
    private var smoothedAimX = 0f
    private var smoothedAimY = 0f
    private var lastMouseEventTime = Long.MIN_VALUE
    private var lastMouseAction = -1

    fun ensureInitialized(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = AppPreferences(context.applicationContext)
        _globalSettings.value = prefs.keyboardMouseSettings
        publishEffectiveSettings()
        runCatching {
            val inputManager = context.getSystemService(InputManager::class.java)
            inputManager?.registerInputDeviceListener(
                object : InputManager.InputDeviceListener {
                    override fun onInputDeviceAdded(deviceId: Int) = refreshConnections()

                    override fun onInputDeviceRemoved(deviceId: Int) = refreshConnections()

                    override fun onInputDeviceChanged(deviceId: Int) = refreshConnections()
                },
                Handler(Looper.getMainLooper())
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to register input-device listener", error)
        }
        scope.launch {
            prefs.keyboardMouseSettingsFlow.collect { newSettings ->
                applyGlobalSettings(newSettings)
            }
        }
        scope.launch {
            while (isActive) {
                runCatching { refreshConnections() }
                    .onFailure { error -> Log.w(TAG, "Input-device poll failed", error) }
                delay(CONNECTION_POLL_INTERVAL_MS.milliseconds)
            }
        }
        refreshConnections()
    }

    fun attachSink(newSink: KeyboardMousePadSink?) {
        if (sink === newSink) return
        releaseAll()
        sink = newSink
    }

    /** Detaches only if [expected] is still the active sink, avoiding stale activity teardown. */
    fun detachSink(expected: KeyboardMousePadSink) {
        if (sink === expected) attachSink(null)
    }

    /** Forces a fresh device scan; called by activities when they regain focus. */
    fun refreshConnectionState() {
        runCatching { refreshConnections() }
            .onFailure { error -> Log.w(TAG, "Explicit input-device refresh failed", error) }
    }

    /** The activity owns the Android pointer-capture primitive; the manager owns policy. */
    fun setPointerCaptureListener(listener: ((Boolean) -> Unit)?) {
        pointerCaptureListener = listener
        pointerCaptureRequested = false
        updatePointerCapture()
    }

    fun setEmulationInputEnabled(enabled: Boolean) {
        if (emulationInputEnabled == enabled) return
        emulationInputEnabled = enabled
        if (!enabled) {
            releaseAll()
            resetMouseDeltaAnchor()
        }
        updatePointerCapture()
    }

    /** Called when the activity gains window focus so a deferred capture can be retried. */
    fun refreshPointerCapture() {
        pointerCaptureRequested = false
        updatePointerCapture()
    }

    /** Menus and light-gun aiming need the system pointer, so capture must pause. */
    fun setPointerCaptureSuppressed(suppressed: Boolean) {
        if (pointerCaptureSuppressed == suppressed) return
        pointerCaptureSuppressed = suppressed
        pointerCaptureRequested = false
        updatePointerCapture()
    }

    /** Applies a game-specific profile, or null to fall back to the global settings. */
    fun setPerGameOverride(settings: KeyboardMouseSettings?) {
        if (_gameOverride.value == settings) return
        _gameOverride.value = settings
        publishEffectiveSettings()
    }

    fun startKeyboardBindingCapture(actionId: String, onCaptured: (Int) -> Unit) {
        captureCallback = onCaptured
        backCaptureUpPending = false
        _bindingCapture.value = BindingCapture(BindingSource.KEYBOARD, actionId)
        updatePointerCapture()
    }

    fun startMouseBindingCapture(actionId: String, onCaptured: (Int) -> Unit) {
        captureCallback = onCaptured
        backCaptureUpPending = false
        _bindingCapture.value = BindingCapture(BindingSource.MOUSE, actionId)
        updatePointerCapture()
    }

    fun cancelBindingCapture() {
        captureCallback = null
        _bindingCapture.value = null
        updatePointerCapture()
    }

    // ------------------------------------------------------------------
    // Binding capture
    // ------------------------------------------------------------------

    fun handleKeyBindingCapture(event: KeyEvent): Boolean {
        val capture = _bindingCapture.value
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (capture != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    cancelBindingCapture()
                    backCaptureUpPending = true
                }
                return true
            }
            if (backCaptureUpPending && event.action == KeyEvent.ACTION_UP) {
                backCaptureUpPending = false
                return true
            }
        }
        if (capture == null) return false
        if (event.action == KeyEvent.ACTION_UP) return true
        if (capture.source != BindingSource.KEYBOARD) return false
        if (isGamepadKeyEvent(event)) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) return true
        if (event.keyCode in SYSTEM_KEY_CODES) {
            cancelBindingCapture()
            return false
        }
        val callback = captureCallback
        captureCallback = null
        _bindingCapture.value = null
        updatePointerCapture()
        callback?.invoke(event.keyCode)
        return true
    }

    fun handleMouseBindingCapture(event: MotionEvent): Boolean {
        val capture = _bindingCapture.value ?: return false
        if (capture.source != BindingSource.MOUSE) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelBindingCapture()
            return true
        }
        val button = when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> mouseButtonForMask(event.actionButton)
            MotionEvent.ACTION_DOWN -> firstPressedMouseButton(event.buttonState)
            else -> null
        } ?: return true
        val callback = captureCallback
        captureCallback = null
        _bindingCapture.value = null
        updatePointerCapture()
        callback?.invoke(button)
        return true
    }

    // ------------------------------------------------------------------
    // Live keyboard input
    // ------------------------------------------------------------------

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!emulationInputEnabled) return false
        if (!_settings.value.keyboardEnabled) return false
        if (isGamepadKeyEvent(event)) return false
        val source = event.source
        if (source != 0 && (source and InputDevice.SOURCE_KEYBOARD) == 0) return false
        if (event.keyCode in SYSTEM_KEY_CODES) return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) {
                    return heldKeyboard.containsKey(event.keyCode)
                }
                val padIndex = _settings.value.keyboardTargetPad.coerceIn(0, 1)
                val actionId = resolveKeyboardAction(event.keyCode, padIndex) ?: return false
                pressKeyboardKey(event.keyCode, actionId)
            }
            KeyEvent.ACTION_UP -> {
                val held = heldKeyboard.remove(event.keyCode) ?: return false
                releaseHeldBinding(held, KeyboardMouseBindings.PAD_SOURCE_KEYBOARD)
                true
            }
            else -> false
        }
    }

    private fun pressKeyboardKey(keyCode: Int, actionId: String): Boolean {
        if (heldKeyboard.containsKey(keyCode)) return true
        if (actionId in KeyboardMouseBindings.shortcutActionIds) {
            heldKeyboard[keyCode] = HeldBinding(actionId, shortcut = true)
            _quickActions.tryEmit(actionId)
            return true
        }
        val intensity = if (actionId in KeyboardMouseBindings.analogActionIds) 1f else 0f
        sink?.onAction(
            KeyboardMouseBindings.PAD_SOURCE_KEYBOARD,
            actionId,
            true,
            intensity
        )
        heldKeyboard[keyCode] = HeldBinding(actionId, shortcut = false)
        return true
    }

    private fun resolveKeyboardAction(keyCode: Int, padIndex: Int): String? {
        val custom = _settings.value.keyboardBindingsByPad[padIndex].orEmpty()
        custom.keys.sorted().forEach { actionId ->
            if (KeyboardMouseBindings.resolveKeyboardBinding(actionId, custom) == keyCode) {
                return actionId
            }
        }
        return KeyboardMouseBindings.keyboardDefaults.keys
            .firstOrNull { actionId ->
                KeyboardMouseBindings.resolveKeyboardBinding(actionId, custom) == keyCode
            }
    }

    // ------------------------------------------------------------------
    // Live mouse input
    // ------------------------------------------------------------------

    fun handleMouseButtonEvent(
        event: MotionEvent,
        fromCapturedPointer: Boolean = false
    ): Boolean {
        if (!emulationInputEnabled || !_settings.value.mouseEnabled) return false
        if (!fromCapturedPointer && !event.isMouseSource()) return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> {
                val button = mouseButtonForMask(event.actionButton) ?: return false
                pressMouseButton(
                    KeyboardMouseBindings.swapMouseButton(button, _settings.value.mouseLeftHanded)
                )
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                val button = mouseButtonForMask(event.actionButton) ?: return false
                releaseMouseButton(
                    KeyboardMouseBindings.swapMouseButton(button, _settings.value.mouseLeftHanded)
                )
            }
            MotionEvent.ACTION_DOWN -> {
                val button = firstPressedMouseButton(event.buttonState) ?: return false
                pressMouseButton(
                    KeyboardMouseBindings.swapMouseButton(button, _settings.value.mouseLeftHanded)
                )
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> releaseAllMouseButtons()
            else -> false
        }
    }

    private fun pressMouseButton(button: Int): Boolean {
        if (heldMouse.containsKey(button)) return true
        val padIndex = _settings.value.mouseTargetPad.coerceIn(0, 1)
        val actionId = resolveMouseAction(button, padIndex) ?: return false
        if (actionId in KeyboardMouseBindings.shortcutActionIds) {
            heldMouse[button] = HeldBinding(actionId, shortcut = true)
            _quickActions.tryEmit(actionId)
            return true
        }
        val intensity = if (actionId in KeyboardMouseBindings.analogActionIds) 1f else 0f
        sink?.onAction(
            KeyboardMouseBindings.PAD_SOURCE_MOUSE,
            actionId,
            true,
            intensity
        )
        heldMouse[button] = HeldBinding(actionId, shortcut = false)
        return true
    }

    private fun releaseMouseButton(button: Int): Boolean {
        val held = heldMouse.remove(button) ?: return false
        releaseHeldBinding(held, KeyboardMouseBindings.PAD_SOURCE_MOUSE)
        return true
    }

    private fun releaseAllMouseButtons(): Boolean {
        if (heldMouse.isEmpty()) return false
        val held = heldMouse.values.toList()
        heldMouse.clear()
        held.forEach { releaseHeldBinding(it, KeyboardMouseBindings.PAD_SOURCE_MOUSE) }
        return true
    }

    fun handleMouseMotionEvent(event: MotionEvent): Boolean {
        if (!event.isMouseSource()) return false
        if (!emulationInputEnabled || !_settings.value.mouseEnabled) return false
        if (_settings.value.mouseAimMode == KeyboardMouseBindings.MOUSE_AIM_OFF) {
            resetMouseDeltaAnchor()
            return false
        }
        if (event.eventTime == lastMouseEventTime && event.actionMasked == lastMouseAction) {
            return true
        }
        lastMouseEventTime = event.eventTime
        lastMouseAction = event.actionMasked

        if (event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.actionMasked == MotionEvent.ACTION_MOVE
        ) {
            val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
            val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
            if (dx != 0f || dy != 0f) applyMouseDelta(dx, dy)
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_EXIT,
            MotionEvent.ACTION_OUTSIDE -> {
                resetMouseDeltaAnchor()
                return false
            }
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_MOVE -> {
                val x = event.x
                val y = event.y
                if (lastAbsoluteX.isNaN() || lastAbsoluteY.isNaN()) {
                    lastAbsoluteX = x
                    lastAbsoluteY = y
                    return true
                }
                val dx = x - lastAbsoluteX
                val dy = y - lastAbsoluteY
                lastAbsoluteX = x
                lastAbsoluteY = y
                if (dx != 0f || dy != 0f) applyMouseDelta(dx, dy)
                return true
            }
            else -> return false
        }
    }

    fun handleCapturedPointerEvent(event: MotionEvent): Boolean {
        if (!emulationInputEnabled || !_settings.value.mouseEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            return handleMouseWheelEvent(event, fromCapturedPointer = true)
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (_settings.value.mouseAimMode == KeyboardMouseBindings.MOUSE_AIM_OFF) {
                return false
            }
            applyMouseDelta(event.x, event.y)
            return true
        }
        return handleMouseButtonEvent(event, fromCapturedPointer = true)
    }

    fun handleMouseWheelEvent(
        event: MotionEvent,
        fromCapturedPointer: Boolean = false
    ): Boolean {
        if (!emulationInputEnabled || !_settings.value.mouseEnabled) return false
        if (!fromCapturedPointer && !event.isMouseSource()) return false
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false
        val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (delta == 0f) return false
        val (upAction, downAction) = KeyboardMouseBindings.mouseWheelActions(
            _settings.value.mouseWheelMode
        )
        val actionId = (if (delta > 0f) upAction else downAction) ?: return false
        pulseWheelKey(actionId)
        return true
    }

    private fun pulseWheelKey(actionId: String) {
        wheelReleaseJobs.remove(actionId)?.cancel()
        sink?.onAction(KeyboardMouseBindings.PAD_SOURCE_MOUSE, actionId, true, 1f)
        val pulseMs = _settings.value.mouseWheelPulseMs.coerceIn(
            KeyboardMouseBindings.MOUSE_WHEEL_PULSE_MIN_MS,
            KeyboardMouseBindings.MOUSE_WHEEL_PULSE_MAX_MS
        ).toLong()
        wheelReleaseJobs[actionId] = scope.launch {
            delay(pulseMs.milliseconds)
            sink?.onAction(KeyboardMouseBindings.PAD_SOURCE_MOUSE, actionId, false, 0f)
            wheelReleaseJobs.remove(actionId)
        }
    }

    private fun releaseWheelKeys() {
        for ((actionId, job) in wheelReleaseJobs) {
            job.cancel()
            sink?.onAction(KeyboardMouseBindings.PAD_SOURCE_MOUSE, actionId, false, 0f)
        }
        wheelReleaseJobs.clear()
    }

    private fun applyMouseDelta(dx: Float, dy: Float) {
        val settings = _settings.value
        val (x, y) = KeyboardMouseBindings.normalizeMouseDelta(
            dx = dx,
            dy = dy,
            sensitivityPercent = settings.mouseSensitivity,
            verticalSensitivityPercent = settings.mouseVerticalSensitivity,
            invertX = settings.mouseInvertX,
            invertY = settings.mouseInvertY,
            deadzonePercent = settings.mouseAimDeadzone
        )
        smoothedAimX = KeyboardMouseBindings.smoothAimValue(
            smoothedAimX,
            x,
            settings.mouseSmoothing
        )
        smoothedAimY = KeyboardMouseBindings.smoothAimValue(
            smoothedAimY,
            y,
            settings.mouseSmoothing
        )
        if (smoothedAimX == 0f && smoothedAimY == 0f) return
        val stick = when (settings.mouseAimMode) {
            KeyboardMouseBindings.MOUSE_AIM_LEFT_STICK -> 0
            else -> 1
        }
        sink?.onMouseAim(stick, smoothedAimX, smoothedAimY)
        scheduleAimRelease()
    }

    private fun scheduleAimRelease() {
        aimReleaseJob?.cancel()
        aimReleaseJob = scope.launch {
            delay(MOUSE_AIM_IDLE_RELEASE_MS.milliseconds)
            releaseAimAxes()
        }
    }

    private fun releaseAimAxes() {
        aimReleaseJob?.cancel()
        aimReleaseJob = null
        val hadAim = smoothedAimX != 0f || smoothedAimY != 0f
        smoothedAimX = 0f
        smoothedAimY = 0f
        if (hadAim) {
            sink?.onMouseAim(0, 0f, 0f)
            sink?.onMouseAim(1, 0f, 0f)
        }
    }

    // ------------------------------------------------------------------
    // Cleanup and connection tracking
    // ------------------------------------------------------------------

    fun releaseAll() {
        val keyboard = heldKeyboard.values.toList()
        heldKeyboard.clear()
        keyboard.forEach { releaseHeldBinding(it, KeyboardMouseBindings.PAD_SOURCE_KEYBOARD) }
        val mouse = heldMouse.values.toList()
        heldMouse.clear()
        mouse.forEach { releaseHeldBinding(it, KeyboardMouseBindings.PAD_SOURCE_MOUSE) }
        releaseAimAxes()
        releaseWheelKeys()
        sink?.releaseSource(KeyboardMouseBindings.PAD_SOURCE_KEYBOARD)
        sink?.releaseSource(KeyboardMouseBindings.PAD_SOURCE_MOUSE)
    }

    private fun releaseHeldBinding(held: HeldBinding, source: Int) {
        if (held.shortcut) return
        sink?.onAction(source, held.actionId, false, 0f)
    }

    private fun refreshConnections() {
        var keyboardConnected = false
        var mouseConnected = false
        for (deviceId in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(deviceId) ?: continue
            if (!keyboardConnected && isUsableKeyboard(device)) keyboardConnected = true
            if (!mouseConnected && isUsableMouse(device)) mouseConnected = true
            if (keyboardConnected && mouseConnected) break
        }
        val updated = ConnectionState(keyboardConnected, mouseConnected)
        val previous = _connection.value
        if (updated == previous) return
        if (previous.keyboardConnected && !updated.keyboardConnected) {
            val held = heldKeyboard.values.toList()
            heldKeyboard.clear()
            held.forEach { releaseHeldBinding(it, KeyboardMouseBindings.PAD_SOURCE_KEYBOARD) }
        }
        if (previous.mouseConnected && !updated.mouseConnected) {
            val held = heldMouse.values.toList()
            heldMouse.clear()
            held.forEach { releaseHeldBinding(it, KeyboardMouseBindings.PAD_SOURCE_MOUSE) }
            releaseAimAxes()
            releaseWheelKeys()
            resetMouseDeltaAnchor()
        }
        _connection.value = updated
        updatePointerCapture()
    }

    private fun isUsableKeyboard(device: InputDevice): Boolean {
        if (device.isVirtual) return false
        if (device.keyboardType == InputDevice.KEYBOARD_TYPE_NONE) return false
        val sources = device.sources
        if ((sources and InputDevice.SOURCE_KEYBOARD) == 0) return false
        if ((sources and GAMEPAD_SOURCES) != 0) return false
        if (isSystemControlDevice(device)) return false
        val name = device.name.lowercase()
        if (FINGERPRINT_UINPUT_TOKENS.any { name.contains(it) }) return false
        if (name in INTERNAL_KEYBOARD_NAMES || name.contains("headset jack")) return false
        if (name.startsWith("uinput")) return false
        val hasTypingKeys = device.hasKeys(
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_1
        ).all { it }
        return hasTypingKeys || (device.isExternal && name.isNotBlank())
    }

    private fun isUsableMouse(device: InputDevice): Boolean {
        if (device.isVirtual) return false
        val sources = device.sources
        val hasMouseSource =
            (sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                (sources and InputDevice.SOURCE_MOUSE_RELATIVE) ==
                InputDevice.SOURCE_MOUSE_RELATIVE
        if (!hasMouseSource) return false
        if ((sources and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) {
            return false
        }
        val name = device.name.lowercase()
        if (name.startsWith("uinput")) return false
        if (name.contains("touchscreen") || name.contains("headset jack")) return false
        if (FINGERPRINT_UINPUT_TOKENS.any { name.contains(it) }) return false
        return true
    }

    private fun applyGlobalSettings(newSettings: KeyboardMouseSettings) {
        if (_globalSettings.value == newSettings) return
        _globalSettings.value = newSettings
        if (_gameOverride.value == null) publishEffectiveSettings()
    }

    private fun publishEffectiveSettings() {
        val newSettings = _gameOverride.value ?: _globalSettings.value
        val previous = _settings.value
        if (previous == newSettings) {
            updatePointerCapture()
            return
        }
        val bindingsChanged =
            previous.keyboardBindingsByPad != newSettings.keyboardBindingsByPad ||
                previous.mouseBindingsByPad != newSettings.mouseBindingsByPad ||
                previous.keyboardTargetPad != newSettings.keyboardTargetPad ||
                previous.mouseTargetPad != newSettings.mouseTargetPad ||
                previous.mouseLeftHanded != newSettings.mouseLeftHanded ||
                previous.mouseWheelMode != newSettings.mouseWheelMode
        val enabledChanged =
            previous.keyboardEnabled != newSettings.keyboardEnabled ||
                previous.mouseEnabled != newSettings.mouseEnabled
        if (bindingsChanged || enabledChanged) releaseAll()
        if (previous.mouseAimMode != newSettings.mouseAimMode) releaseAimAxes()
        _settings.value = newSettings
        updatePointerCapture()
    }

    private fun updatePointerCapture() {
        val settings = _settings.value
        val shouldCapture = emulationInputEnabled &&
            settings.mouseEnabled &&
            settings.mouseCaptureCursor &&
            settings.mouseAimMode != KeyboardMouseBindings.MOUSE_AIM_OFF &&
            !pointerCaptureSuppressed &&
            _bindingCapture.value == null &&
            _connection.value.mouseConnected
        if (shouldCapture == pointerCaptureRequested) return
        pointerCaptureRequested = shouldCapture
        releaseAimAxes()
        resetMouseDeltaAnchor()
        pointerCaptureListener?.invoke(shouldCapture)
    }

    private fun resetMouseDeltaAnchor() {
        lastAbsoluteX = Float.NaN
        lastAbsoluteY = Float.NaN
        smoothedAimX = 0f
        smoothedAimY = 0f
        lastMouseEventTime = Long.MIN_VALUE
        lastMouseAction = -1
    }

    private fun resolveMouseAction(button: Int, padIndex: Int): String? {
        val custom = _settings.value.mouseBindingsByPad[padIndex].orEmpty()
        for ((actionId, _) in custom) {
            if (KeyboardMouseBindings.resolveMouseBinding(actionId, custom) == button) {
                return actionId
            }
        }
        return KeyboardMouseBindings.mouseDefaults.entries
            .firstOrNull { (actionId, code) ->
                code == button && KeyboardMouseBindings.resolveMouseBinding(actionId, custom) == code
            }
            ?.key
    }

    private fun MotionEvent.isMouseSource(): Boolean =
        isFromSource(InputDevice.SOURCE_MOUSE) ||
            isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)

    private fun isGamepadKeyEvent(event: KeyEvent): Boolean {
        if ((event.source and GAMEPAD_SOURCES) != 0) return true
        val device = event.device ?: return false
        return (device.sources and GAMEPAD_SOURCES) != 0 &&
            InputDeviceClassifier.isPhysicalGameController(device.id)
    }

    private fun isSystemControlDevice(device: InputDevice): Boolean {
        val name = device.name.lowercase()
        return name.contains("system control") || name.contains("consumer control")
    }

    private fun mouseButtonForMask(mask: Int): Int? = when (mask) {
        MotionEvent.BUTTON_PRIMARY -> KeyboardMouseBindings.MOUSE_BUTTON_LEFT
        MotionEvent.BUTTON_SECONDARY -> KeyboardMouseBindings.MOUSE_BUTTON_RIGHT
        MotionEvent.BUTTON_TERTIARY -> KeyboardMouseBindings.MOUSE_BUTTON_MIDDLE
        MotionEvent.BUTTON_BACK -> KeyboardMouseBindings.MOUSE_BUTTON_BACK
        MotionEvent.BUTTON_FORWARD -> KeyboardMouseBindings.MOUSE_BUTTON_FORWARD
        else -> null
    }

    private fun firstPressedMouseButton(buttonState: Int): Int? {
        for (mask in MOUSE_BUTTON_MASKS) {
            if ((buttonState and mask) != 0) return mouseButtonForMask(mask)
        }
        return null
    }

    private val MOUSE_BUTTON_MASKS = intArrayOf(
        MotionEvent.BUTTON_PRIMARY,
        MotionEvent.BUTTON_SECONDARY,
        MotionEvent.BUTTON_TERTIARY,
        MotionEvent.BUTTON_BACK,
        MotionEvent.BUTTON_FORWARD
    )
}
