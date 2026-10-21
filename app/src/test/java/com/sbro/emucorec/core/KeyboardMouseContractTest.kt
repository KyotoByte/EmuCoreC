package com.sbro.emucorec.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardMouseContractTest {
    @Test
    fun keyboardMouseSettingsArePersistedGloballyBackedUpAndPerGame() {
        val preferences = javaSource("data/AppPreferences.kt")
        val backup = javaSource("core/SettingsBackupRepository.kt")
        val perGame = javaSource("core/Ps3GameSettingsRepository.kt")

        assertTrue("Global settings must be readable", "val keyboardMouseSettings:" in preferences)
        assertTrue("Global settings must be written", "fun setKeyboardMouseSettings(" in preferences)
        assertTrue("Global settings must expose a live flow", "keyboardMouseSettingsFlow" in preferences)
        assertTrue("Backup must export keyboard/mouse settings", "\"keyboardMouse\"" in backup)
        assertTrue("Backup must restore keyboard/mouse settings", "KeyboardMouseSettingsCodec.decode" in backup)
        assertTrue("Per-game profiles must persist keyboard/mouse", "keyboardMouse = KeyboardMouseSettingsCodec.decode" in perGame)
        assertTrue("Per-game profiles must write keyboard/mouse", "KeyboardMouseSettingsCodec.encode" in perGame)
        assertTrue("Per-game profiles must support inheriting global settings", "fun clearKeyboardMouse(" in perGame)
    }

    @Test
    fun emulationActivityRoutesKeyboardMouseEventsAndOwnsPointerCapture() {
        val emulator = javaSource("core/ps3/Emulator.kt")

        assertTrue("Key binding capture must be routed", "handleKeyBindingCapture(event)" in emulator)
        assertTrue("Live keyboard input must be routed", "handleKeyEvent(event)" in emulator)
        assertTrue("Mouse binding capture must be routed", "handleMouseBindingCapture(event)" in emulator)
        assertTrue("Mouse buttons must be routed", "handleMouseButtonEvent(event)" in emulator)
        assertTrue("Mouse motion must be routed", "handleMouseMotionEvent(event)" in emulator)
        assertTrue("Mouse wheel must be routed", "handleMouseWheelEvent(event)" in emulator)
        assertTrue("Captured pointer input must be routed", "setOnCapturedPointerListener" in emulator)
        assertTrue("Pointer capture must be requested", "requestPointerCapture()" in emulator)
        assertTrue("Keyboard/mouse sink must attach to the overlay", "attachSink(inputOverlay)" in emulator)
        assertTrue("Per-game overrides must load for the running title", "refreshKeyboardMouseRuntimeSettings()" in emulator)
    }

    @Test
    fun overlayKeepsKeyboardAndMouseIsolatedFromPhysicalAndTouchInput() {
        val overlay = javaSource("core/ps3/overlay/InputOverlay.kt")

        assertTrue("Keyboard lane must be explicit", "keyboardDigital1" in overlay)
        assertTrue("Mouse lane must be explicit", "mouseDigital1" in overlay)
        assertTrue("Mouse aim lane must be explicit", "mouseAimAxes" in overlay)
        assertTrue("Lanes must merge into one core snapshot", "digital1 or keyboardDigital1 or mouseDigital1" in overlay)
        assertTrue("Mapped input must attach the virtual pad", "mappedInputSeen" in overlay)
        assertTrue("The existing core bridge must remain the only connection", "Ps3Core.instance.overlayPadData(" in overlay)
        assertTrue("Releasing one source must not reset others", "releaseSource(" in overlay)
    }

    @Test
    fun everyUiEntryPointUsesTheSharedKeyboardMouseUi() {
        val settings = javaSource("ui/settings/SettingsTabContent.kt")
        val gameManager = javaSource("ui/gamemanager/GameManagerScreen.kt")
        val overlay = javaSource("ui/emulation/EmulationOverlay.kt")
        val menu = javaSource("ui/emulation/EmulationMenu.kt")

        assertTrue("Global controls tab must show the section", "GlobalKeyboardMouseSettingsSection()" in settings)
        assertTrue("Per-game controls tab must show the section", "PerGameKeyboardMouseSettingsSection(" in gameManager)
        assertTrue("In-game settings dialog must be reachable", "KeyboardMouseSettingsDialog(" in overlay)
        assertTrue("In-game menu must expose the entry", "onOpenKeyboardMouseSettings" in menu)
        assertTrue("Quick save/load shortcuts must be consumed", "KeyboardMouseManager.quickActions" in overlay)
        assertTrue("Menu must pause live mouse capture", "setPointerCaptureSuppressed(menuOpen || controlsEditMode)" in overlay)
    }

    private fun javaSource(relative: String): String = appModule()
        .resolve("src/main/java/com/sbro/emucorec")
        .resolve(relative)
        .readText()

    private fun appModule(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        return sequenceOf(workingDirectory, workingDirectory.resolve("app"))
            .firstOrNull { Files.isDirectory(it.resolve("src/main")) }
            ?: error("Unable to locate Android app module from $workingDirectory")
    }
}
