package com.sbro.emucorec

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.sbro.emucorec.core.EmulatorStorage
import com.sbro.emucorec.core.NativeSaveStateBackend
import com.sbro.emucorec.core.Ps3IsoParser
import com.sbro.emucorec.core.Ps3Runtime
import com.sbro.emucorec.core.SaveStateController
import com.sbro.emucorec.core.SaveStateOutcome
import com.sbro.emucorec.core.ps3.Emulator
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.rpcsx.EmulatorState
import net.rpcsx.ProgressRepository
import net.rpcsx.RPCSX
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaveStateLifecycleTest {
    @Test
    fun appOwnedTruncatedPayloadWithValidVersionsIsRejectedBeforeBoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(Ps3Runtime.ensureInitialized(context))
        assertEquals(EmulatorState.Stopped, Ps3Runtime.state())
        val directory = File(EmulatorStorage.ps3Root(context), "config/savestates/EMUCINTEGRITYTEST/emucorec_slots")
        directory.mkdirs()
        val file = File(directory, "slot0.SAVESTAT")
        val header = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("RPCS3SAV".toByteArray(Charsets.US_ASCII))
            .put(1).put(0).putLong(19).put(1).put(1).putShort(0).putShort(25).array()
        try {
            file.writeBytes(header)
            assertNotEquals("A supported version table does not make a truncated payload valid", 0,
                RPCSX.instance.boot(file.absolutePath))
            assertEquals(EmulatorState.Stopped, Ps3Runtime.state())
        } finally { file.delete(); directory.delete(); directory.parentFile?.delete() }
    }

    @Test
    fun stoppedCoreRejectsSaveWithoutLeavingProgressPending() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(Ps3Runtime.ensureInitialized(context))
        assertTrue("This core must expose the save-state bridge", RPCSX.instance.saveStatesSupported())
        assertEquals(EmulatorState.Stopped.ordinal, RPCSX.instance.getState())
        val controller = SaveStateController(NativeSaveStateBackend())
        assertEquals(SaveStateOutcome.Failed, controller.save(0))
        assertFalse(controller.busy.value)
        assertTrue(ProgressRepository.progress.value.isEmpty())
    }

    /** Opt in with -e saveGamePath <a playable GTA San Andreas disc>. */
    @Test(timeout = 300_000)
    fun realGameCanSaveRestoreRejectCorruptionAndExitDuringLoad() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("saveGamePath")
        assumeNotNull(path)
        val context = instrumentation.targetContext
        assertTrue(Ps3Runtime.ensureInitialized(context))
        assertTrue(RPCSX.instance.saveStatesSupported())
        val game = requireNotNull(Ps3IsoParser.parse(context, File(requireNotNull(path))))
        val controller = SaveStateController(NativeSaveStateBackend())
        var activity = instrumentation.startActivitySync(Intent(context, Emulator::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Emulator.EXTRA_GAME_PATH, path)
            .putExtra(Emulator.EXTRA_TITLE_ID, game.titleId))
        val createdFiles = mutableListOf<File>()
        try {
            waitForState(EmulatorState.Running)
            SystemClock.sleep(20_000)
            RPCSX.instance.overlayPadData(0, 0x40, 128, 128, 128, 128)
            SystemClock.sleep(200)
            RPCSX.instance.overlayPadData(0, 0, 128, 128, 128, 128)
            SystemClock.sleep(20_000)
            // The first press skips the intro; the second starts from the title menu.
            RPCSX.instance.overlayPadData(0, 0x40, 128, 128, 128, 128)
            SystemClock.sleep(200)
            RPCSX.instance.overlayPadData(0, 0, 128, 128, 128, 128)
            SystemClock.sleep(25_000)
            RPCSX.instance.overlayPadData(0, 0x40, 128, 128, 128, 128)
            SystemClock.sleep(200)
            RPCSX.instance.overlayPadData(0, 0, 128, 128, 128, 128)
            SystemClock.sleep(25_000)
            // Skip the opening cinematic so the snapshot contains controllable gameplay.
            RPCSX.instance.overlayPadData(0x08, 0, 128, 128, 128, 128)
            SystemClock.sleep(200)
            RPCSX.instance.overlayPadData(0, 0, 128, 128, 128, 128)
            SystemClock.sleep(15_000)
            controller.refresh()
            val empty = (0 until SaveStateController.SLOT_COUNT)
                .filter { slot -> controller.slots.value.none { it.index == slot } }
            assumeTrue("Device validation needs two unused slots", empty.size >= 2)
            val slot = empty.last()
            val corruptSlot = empty[empty.lastIndex - 1]
            val directory = File(EmulatorStorage.ps3Root(context), "config/savestates/${game.titleId}/emucorec_slots")
            val savedFile = File(directory, "slot$slot.SAVESTAT.zst")
            assertFalse(savedFile.exists())
            createdFiles += savedFile
            val savedIntegrity = File(savedFile.path + ".integrity")
            createdFiles += savedIntegrity
            assertEquals(SaveStateOutcome.Empty, controller.load(slot))
            RPCSX.instance.pause()
            waitForState(EmulatorState.Paused)
            screenshot("save-before")
            assertEquals(SaveStateOutcome.Success, withTimeout(120_000) { controller.save(slot) })
            waitForState(EmulatorState.Running)
            assertEquals(game.titleId, RPCSX.instance.getTitleId())
            val saved = controller.slots.value.single { it.index == slot }
            assertTrue(saved.sizeBytes > 1024)

            assertTrue("The numbered slot must be committed", savedFile.isFile)
            assertTrue("The slot must include committed integrity metadata", savedIntegrity.isFile)
            val corrupt = File(directory, "slot$corruptSlot.SAVESTAT.zst")
            assertFalse(corrupt.exists())
            corrupt.writeBytes(ByteArray(2048))
            createdFiles += corrupt
            assertEquals(SaveStateOutcome.Failed, withTimeout(10_000) { controller.load(corruptSlot) })
            assertEquals(EmulatorState.Running.ordinal, RPCSX.instance.getState())
            assertEquals(game.titleId, RPCSX.instance.getTitleId())

            // An intact compressed prefix and supported version table still
            // cannot prove a complete body. The original digest must reject it.
            corrupt.writeBytes(savedFile.inputStream().use { it.readNBytes(4096) })
            val corruptIntegrity = File(corrupt.path + ".integrity")
            createdFiles += corruptIntegrity
            savedIntegrity.copyTo(corruptIntegrity, overwrite = false)
            assertEquals(SaveStateOutcome.Failed, withTimeout(10_000) { controller.load(corruptSlot) })
            assertEquals(EmulatorState.Running, Ps3Runtime.state())

            RPCSX.instance.overlayPadData(0, 0, 128, 0, 128, 128)
            SystemClock.sleep(1500)
            RPCSX.instance.overlayPadData(0, 0, 128, 128, 128, 128)
            screenshot("save-moved")
            assertEquals(SaveStateOutcome.Success, withTimeout(120_000) { controller.load(slot) })
            waitForState(EmulatorState.Running)
            SystemClock.sleep(1000)
            screenshot("save-restored")
            assertEquals(game.titleId, RPCSX.instance.getTitleId())
            assertEquals(saved, controller.slots.value.single { it.index == slot })

            val load = async(start = CoroutineStart.UNDISPATCHED) { controller.load(slot) }
            assertFalse(load.isCompleted)
            val exitStart = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { activity.finish() }
            // onDestroy() may run after finish(); this marker measures whether it blocks the UI.
            SystemClock.sleep(100)
            instrumentation.runOnMainSync {}
            assertTrue("Exiting must not wait for state I/O on the UI thread",
                SystemClock.elapsedRealtime() - exitStart < 4000)
            assertEquals(SaveStateOutcome.Failed, withTimeout(120_000) { load.await() })
            waitForState(EmulatorState.Stopped)
            assertTrue(ProgressRepository.progress.value.isEmpty())
            waitForDestroyed(activity)

            // The manager uses ordinary activity boot, a separate entry point
            // from the in-game load request. Both must reject damaged input.
            activity = instrumentation.startActivitySync(Intent(context, Emulator::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Emulator.EXTRA_GAME_PATH, corrupt.absolutePath)
                .putExtra(Emulator.EXTRA_TITLE_ID, game.titleId))
            waitForDestroyed(activity)
            waitForState(EmulatorState.Stopped)

            activity = instrumentation.startActivitySync(Intent(context, Emulator::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Emulator.EXTRA_GAME_PATH, savedFile.absolutePath)
                .putExtra(Emulator.EXTRA_TITLE_ID, game.titleId))
            waitForState(EmulatorState.Running)
            SystemClock.sleep(1000)
            screenshot("save-cold-restored")
            assertEquals(game.titleId, RPCSX.instance.getTitleId())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            waitForDestroyed(activity)
            waitForState(EmulatorState.Stopped)
            createdFiles.filter(File::exists).forEach { assertTrue(it.delete()) }
        }
    }

    private fun waitForDestroyed(activity: android.app.Activity) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var destroyed = false
        while (!destroyed && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                destroyed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getLifecycleStageOf(activity) == Stage.DESTROYED
            }
            if (!destroyed) SystemClock.sleep(50)
        }
        assertTrue("Emulation activity must close after VM shutdown", destroyed)
    }

    private fun waitForState(state: EmulatorState) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (RPCSX.instance.getState() != state.ordinal && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
        }
        assertEquals(state.ordinal, RPCSX.instance.getState())
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(instrumentation.targetContext.getExternalFilesDir(null), "test-results/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
