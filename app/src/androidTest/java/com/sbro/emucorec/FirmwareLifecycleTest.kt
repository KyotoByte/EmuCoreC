package com.sbro.emucorec

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucorec.core.EmulatorStorage
import com.sbro.emucorec.core.Ps3InstallBridge
import com.sbro.emucorec.core.Ps3Runtime
import com.sbro.emucorec.data.AppPreferences
import com.sbro.emucorec.data.InstalledGameRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.rpcsx.FirmwareRepository
import net.rpcsx.FirmwareStatus
import net.rpcsx.ProgressRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the firmware and game-folder paths supplied by the device test runner. */
@RunWith(AndroidJUnit4::class)
class FirmwareLifecycleTest {
    @Test(timeout = 60_000)
    fun libraryScanIncludesEachDeviceImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gamesDirectory = File(InstrumentationRegistry.getArguments().getString("gamesPath", "/sdcard/PS3"))
        val images = gamesDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("iso", ignoreCase = true) }
        assertTrue("Provide a gamesPath containing PS3 ISO images", images.isNotEmpty())
        val preferences = AppPreferences(context)
        val previousDirectories = preferences.gameDirectories
        try {
            preferences.gameDirectories = setOf(gamesDirectory.absolutePath)
            val library = InstalledGameRepository().loadInstalledGames(context)
            images.forEach { image ->
                assertTrue("Missing device image: ${image.name}",
                    library.any { game -> game.installPath == image.absolutePath })
            }
        } finally {
            preferences.gameDirectories = previousDirectories
        }
    }

    @Test(timeout = 900_000)
    fun installAndReinstallFinishBeforeLibraryScanning() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val firmware = File(arguments.getString("firmwarePath", "/sdcard/Download/PS3UPDAT.PUP"))
        val gamesDirectory = File(arguments.getString("gamesPath", "/sdcard/PS3"))
        assertTrue("Provide a firmwarePath accessible to the app", firmware.isFile)
        val discImages = gamesDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("iso", ignoreCase = true) }
        assertTrue("Provide a gamesPath containing PS3 ISO images", discImages.isNotEmpty())
        val preferences = AppPreferences(context)
        val previousDirectories = preferences.gameDirectories
        try {
            preferences.gameDirectories = setOf(gamesDirectory.absolutePath)
            withTimeout(840_000) {
                withContext(Dispatchers.IO) {
                    assertTrue(Ps3Runtime.ensureInitialized(context))
                    repeat(2) {
                        val version = Ps3InstallBridge.installFirmware(context, firmware.absolutePath)
                        assertNotNull("The firmware operation must finish successfully", version)
                        assertTrue(EmulatorStorage.hasInstalledFirmware(context))
                        assertEquals(FirmwareStatus.Compiled, FirmwareRepository.status.value)
                        assertTrue("Firmware compilation must produce executable cache files",
                            File(EmulatorStorage.ps3Root(context), "cache").walkTopDown()
                                .any { file -> file.isFile && file.length() > 0 &&
                                    (file.name.endsWith(".obj") || file.name.endsWith(".obj.gz")) })
                        assertTrue("No install operation may be stranded", ProgressRepository.progress.value.isEmpty())
                        val library = InstalledGameRepository().loadInstalledGames(context)
                        discImages.forEach { image ->
                            assertTrue("Missing image after installation: ${image.name}",
                                library.any { game -> game.installPath == image.absolutePath })
                        }
                        Ps3Runtime.stop()
                        assertTrue(Ps3Runtime.ensureInitialized(context))
                        val threads = Thread.getAllStackTraces().keys
                        listOf("ps3-main-pump", "ps3-compile-pump").forEach { name ->
                            assertEquals("Native processors must survive stop without duplication: $name",
                                1, threads.count { thread -> thread.isAlive && thread.name == name })
                        }
                    }
                }
            }
        } finally {
            preferences.gameDirectories = previousDirectories
        }
    }
}
