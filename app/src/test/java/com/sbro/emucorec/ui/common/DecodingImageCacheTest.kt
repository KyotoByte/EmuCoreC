package com.sbro.emucorec.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DecodingImageCacheTest {
    @Test
    fun cachedImageRemainsAvailableWhileAnotherImageIsDecoding() {
        val images = mutableMapOf("cached" to "ready")
        val decoding = CountDownLatch(1)
        val finishDecode = CountDownLatch(1)
        val cache = DecodingImageCache(
            read = images::get,
            write = { path, image -> images[path] = image },
            evict = images::clear,
            decode = {
                decoding.countDown()
                check(finishDecode.await(5, TimeUnit.SECONDS))
                "decoded"
            }
        )
        val workers = Executors.newFixedThreadPool(2)
        try {
            val loading = workers.submit<String?> { cache.load("other") }
            assertTrue("Background decoder did not start", decoding.await(5, TimeUnit.SECONDS))
            val reading = workers.submit<String?> { cache.get("cached") }
            assertEquals("ready", reading.get(1, TimeUnit.SECONDS))
            finishDecode.countDown()
            assertEquals("decoded", loading.get(5, TimeUnit.SECONDS))
            assertEquals("decoded", cache.get("other"))
        } finally {
            finishDecode.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun clearDoesNotLetAnEarlierDecodeRepopulateTheCache() {
        val images = mutableMapOf<String, String>()
        val decoding = CountDownLatch(1)
        val finishDecode = CountDownLatch(1)
        val cache = DecodingImageCache(
            read = images::get,
            write = { path, image -> images[path] = image },
            evict = images::clear,
            decode = {
                decoding.countDown()
                check(finishDecode.await(5, TimeUnit.SECONDS))
                "decoded"
            }
        )
        val workers = Executors.newSingleThreadExecutor()
        try {
            val loading = workers.submit<String?> { cache.load("image") }
            assertTrue(decoding.await(5, TimeUnit.SECONDS))
            cache.clear()
            finishDecode.countDown()
            assertEquals("decoded", loading.get(5, TimeUnit.SECONDS))
            assertNull(cache.get("image"))
        } finally {
            finishDecode.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun cachedImagesSkipDecodeAndClearEvictsThem() {
        val images = mutableMapOf("cached" to "ready")
        val cache = DecodingImageCache(
            read = images::get,
            write = { path, image -> images[path] = image },
            evict = images::clear,
            decode = { error("A cache hit must not decode") }
        )
        assertEquals("ready", cache.load("cached"))
        cache.clear()
        assertNull(cache.get("cached"))
        assertNull(cache.load(null))
        assertNull(cache.load(" "))
    }
}
