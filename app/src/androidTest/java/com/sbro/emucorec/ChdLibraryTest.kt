package com.sbro.emucorec

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucorec.core.Ps3IsoParser
import com.sbro.emucorec.core.Ps3Runtime
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import com.sbro.emucorec.core.Ps3Core
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChdLibraryTest {
    @Test
    fun compressedDvdAndCdKeepMetadataAndCovers() = withFixtures { context, directory ->
        listOf("dvd-lzma.chd", "dvd-zlib.chd", "dvd-zstd.chd", "cd.chd", "version-fallback.chd").forEach { name ->
            val image = copyFixture(name, directory)
            assertDisc(context, image)
        }
    }

    @Test
    fun encryptedChdUsesItsSidecarKey() = withFixtures { context, directory ->
        val image = copyFixture("encrypted.chd", directory)
        File(directory, "encrypted.dkey").writeBytes(ByteArray(16) { it.toByte() })
        assertDisc(context, image)
    }

    @Test
    fun decryptedChdIgnoresALeftoverKey() = withFixtures { context, directory ->
        val image = copyFixture("dvd-lzma.chd", directory)
        File(directory, "dvd-lzma.dkey").writeBytes(ByteArray(16))
        assertDisc(context, image)
    }

    @Test
    fun encryptedChdRejectsAnIncorrectKey() = withFixtures { context, directory ->
        val image = copyFixture("encrypted.chd", directory)
        File(directory, "encrypted.dkey").writeBytes(ByteArray(16))
        assertNull(Ps3IsoParser.parse(context, image))
    }

    @Test
    fun renamedChdIsRecognizedByItsContent() = withFixtures { context, directory ->
        val image = copyFixture("dvd-lzma.chd", directory)
        val renamed = File(directory, "disc.bin")
        assertTrue(image.renameTo(renamed))
        assertDisc(context, renamed)
    }

    @Test
    fun invalidChdDoesNotCreateAPhantomGame() = withFixtures { context, directory ->
        val image = File(directory, "invalid.chd").apply { writeText("MComprHDinvalid") }
        assertNull(Ps3IsoParser.parse(context, image))
    }

    @Test
    fun concurrentDiscReadsPreserveEmulatorState() = withFixtures { context, directory ->
        val images = listOf("dvd-lzma.chd", "dvd-zlib.chd", "dvd-zstd.chd", "cd.chd")
            .map { copyFixture(it, directory) }
        val state = Ps3Core.instance.getState()
        val titleId = Ps3Core.instance.getTitleId()
        val executor = Executors.newFixedThreadPool(3)
        try {
            executor.invokeAll(List(12) { index -> Callable { assertDisc(context, images[index % images.size]) } })
                .forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(state, Ps3Core.instance.getState())
        assertEquals(titleId, Ps3Core.instance.getTitleId())
    }

    private fun assertDisc(context: Context, image: File) {
        val game = Ps3IsoParser.parse(context, image)
        assertNotNull("Missing ${image.name}", game)
        requireNotNull(game)
        assertEquals("Wrong identity for ${image.name}", "BLES00001", game.titleId)
        assertEquals("CHD Test Disc", game.title)
        assertEquals("01.23", game.version)
        assertEquals("DG", game.category)
        assertEquals("BLES00001-SAVE", game.saveDataId)
        assertEquals(image.absolutePath, game.installPath)
        assertTrue("The disc cover must be readable for ${image.name}",
            game.iconPath?.let { File(it).length() > 0 } == true)
        val expected = InstrumentationRegistry.getInstrumentation().context.assets
            .open("chd/expected-icon.png").use { it.readBytes() }
        assertArrayEquals("Reads spanning CHD hunks must preserve every cover byte",
            expected, File(requireNotNull(game.iconPath)).readBytes())
    }

    private fun copyFixture(name: String, directory: File): File = File(directory, name).apply {
        InstrumentationRegistry.getInstrumentation().context.assets.open("chd/$name").use { input ->
            outputStream().use(input::copyTo)
        }
    }

    private fun withFixtures(test: (Context, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(Ps3Runtime.ensureInitialized(context))
        val directory = kotlin.io.path.createTempDirectory(context.cacheDir.toPath(), "chd-test-").toFile()
        try {
            test(context, directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
