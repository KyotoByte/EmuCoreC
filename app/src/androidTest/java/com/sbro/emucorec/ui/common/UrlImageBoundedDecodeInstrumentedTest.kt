package com.sbro.emucorec.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UrlImageBoundedDecodeInstrumentedTest {
    @Test fun fileUrlLookaheadUsesRequestedDecodeSizeAndWarmsTheVisibleCache() = verify(false)

    @Test fun contentUrlLookaheadUsesRequestedDecodeSizeAndWarmsTheVisibleCache() = verify(true)

    private fun verify(contentUri: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File.createTempFile("large-cover", ".png", directory)
        val source = Bitmap.createBitmap(2048, 4096, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.MAGENTA)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            source.recycle()
        }
        val uri = if (contentUri) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else Uri.fromFile(file)
        val key = uri.toString()
        UrlBitmapMemoryCache.clear()
        try {
            val request = com.sbro.emucorec.ui.library.LibraryArtworkRequest(key, null)
            com.sbro.emucorec.ui.library.prefetchLibraryArtwork(context, request, 256)
            val cached = UrlBitmapMemoryCache.get(key)
            assertNotNull("The actual library lookahead did not warm its image cache", cached)
            assertEquals(256, maxOf(cached!!.width, cached.height))
            assertTrue("A full-size stream bitmap entered memory", cached.allocationByteCount <= 256 * 256 * 4)
            val visible = loadUrlImageBitmap(context, key, null, 256)
            assertTrue("Visible load did not reuse the prefetched bitmap", cached === visible)
            assertEquals(Color.MAGENTA, cached.getPixel(cached.width / 2, cached.height / 2))
        } finally {
            UrlBitmapMemoryCache.clear()
            file.delete()
        }
    }
}
