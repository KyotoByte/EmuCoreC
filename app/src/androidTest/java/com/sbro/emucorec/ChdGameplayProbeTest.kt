package com.sbro.emucorec

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.sbro.emucorec.core.Ps3IsoParser
import com.sbro.emucorec.core.Ps3Runtime
import com.sbro.emucorec.core.ps3.Emulator
import java.io.File
import net.rpcsx.EmulatorState
import net.rpcsx.RPCSX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with -e chdGamePath <path to an installed, playable CHD>. */
@RunWith(AndroidJUnit4::class)
class ChdGameplayProbeTest {
    @Test(timeout = 180_000)
    fun libraryProbesDoNotInterruptAnActiveChdGame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("chdGamePath")
        assumeNotNull(path)
        val context = instrumentation.targetContext
        assertTrue(Ps3Runtime.ensureInitialized(context))
        val game = Ps3IsoParser.parse(context, File(requireNotNull(path)))
        assertNotNull("Provide a readable PS3 CHD", game)
        requireNotNull(game)
        val intent = Intent(context, Emulator::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Emulator.EXTRA_GAME_PATH, path)
            .putExtra(Emulator.EXTRA_TITLE_ID, game.titleId)
        val activity = instrumentation.startActivitySync(intent)
        try {
            val deadline = SystemClock.elapsedRealtime() + 120_000
            while (RPCSX.instance.getState() != EmulatorState.Running.ordinal &&
                SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
            }
            assertEquals("The real CHD must boot", EmulatorState.Running.ordinal, RPCSX.instance.getState())
            assertEquals(game.titleId, RPCSX.instance.getTitleId())
            ChdLibraryTest().concurrentDiscReadsPreserveEmulatorState()
            val rescanned = Ps3IsoParser.parse(context, File(path))
            assertEquals(game.titleId, rescanned?.titleId)
            assertEquals(game.title, rescanned?.title)
            assertEquals(EmulatorState.Running.ordinal, RPCSX.instance.getState())
            assertEquals(game.titleId, RPCSX.instance.getTitleId())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            // finish() returns before onDestroy(), which stops the native game.
            // The next test may install firmware as soon as this one returns.
            val deadline = SystemClock.elapsedRealtime() + 30_000
            var destroyed = false
            while (!destroyed && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    destroyed = ActivityLifecycleMonitorRegistry.getInstance()
                        .getLifecycleStageOf(activity) == Stage.DESTROYED
                }
                if (!destroyed) SystemClock.sleep(50)
            }
            assertTrue("The game activity must finish native teardown", destroyed)
            while (RPCSX.instance.getState() != EmulatorState.Stopped.ordinal &&
                SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            assertEquals(EmulatorState.Stopped.ordinal, RPCSX.instance.getState())
        }
    }
}
