package com.sbro.emucorec.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest

/**
 * Longest edge a downloaded image is decoded at. The largest consumer is the
 * detail hero, well under this bound; decoding an IGDB original (often
 * 1000-1500px) at full size would multiply the memory cache for no visible
 * gain and slow scrolling.
 */
private const val MAX_DECODED_EDGE_PX = 1024

@Composable
fun UrlImage(
    imageUrl: String?,
    contentDescription: String,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
    fallbackPath: String? = null,
    pinInMemory: Boolean = false
) {
    val context = LocalContext.current
    // Start from whatever is already decoded (the max-quality variant, since
    // that is what the producer stores) so tiles do not flash placeholders.
    val initial = imageUrl?.let { UrlBitmapMemoryCache.get(igdbMaximumQualityUrl(it)) }
    val bitmapState = produceState<Bitmap?>(
        initialValue = initial,
        imageUrl,
        fallbackPath,
        context
    ) {
        val loadedBitmap = withContext(Dispatchers.IO) {
            // IGDB images are served as transforms of one original upload, so
            // the original is the maximum quality and the transformed tiers are
            // the graceful degradation ladder when it cannot be fetched.
            val candidates = buildList {
                if (!imageUrl.isNullOrBlank()) {
                    val best = igdbMaximumQualityUrl(imageUrl)
                    add(best)
                    addAll(igdbImageFallbackUrls(best))
                }
            }

            for (candidate in candidates) {
                UrlBitmapMemoryCache.get(candidate)?.let { return@withContext it }

                val cachedFile = UrlImageDiskCache.getCachedFile(context, candidate)
                if (cachedFile != null) {
                    val diskBitmap = decodeBounded(cachedFile.absolutePath)
                    if (diskBitmap != null) {
                        UrlBitmapMemoryCache.put(candidate, diskBitmap, pinned = pinInMemory)
                        return@withContext diskBitmap
                    }
                }

                val fetched = runCatching {
                    if (candidate.startsWith("content://") || candidate.startsWith("file://")) {
                        context.contentResolver.openInputStream(Uri.parse(candidate))?.use(::decodeBounded)
                    } else {
                        val downloaded = UrlImageDiskCache.downloadToCache(context, candidate)
                        if (downloaded != null) {
                            decodeBounded(downloaded.absolutePath)
                        } else {
                            URL(candidate).openStream().use(::decodeBounded)
                        }
                    }
                }.getOrNull()

                if (fetched != null) {
                    UrlBitmapMemoryCache.put(candidate, fetched, pinned = pinInMemory)
                    return@withContext fetched
                }
            }

            // Local file fallback if no remote tier could be loaded
            if (!fallbackPath.isNullOrBlank()) {
                decodeBounded(fallbackPath)
            } else {
                null
            }
        }
        value = loadedBitmap
    }
    val bitmap = bitmapState.value

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = fallbackLabel.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun decodeBounded(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= MAX_DECODED_EDGE_PX ||
        bounds.outHeight / (sample * 2) >= MAX_DECODED_EDGE_PX
    ) {
        sample *= 2
    }

    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun decodeBounded(stream: InputStream): Bitmap? =
    runCatching { BitmapFactory.decodeStream(stream) }.getOrNull()

object UrlImageDiskCache {
    private const val CACHE_DIR_NAME = "image_cache"

    fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCachedFile(context: Context, url: String): File? {
        return runCatching {
            val key = hashKey(url)
            val file = File(getCacheDir(context), key)
            if (file.exists() && file.length() > 0) file else null
        }.getOrNull()
    }

    fun downloadToCache(context: Context, url: String): File? {
        return runCatching {
            val cacheDir = getCacheDir(context)
            val key = hashKey(url)
            val tempFile = File(cacheDir, "$key.tmp")
            val targetFile = File(cacheDir, key)

            val conn = URL(url).openConnection()
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.getInputStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                targetFile
            } else {
                tempFile.delete()
                null
            }
        }.getOrNull()
    }

    fun getCacheSizeBytes(context: Context): Long {
        return runCatching {
            val dir = getCacheDir(context)
            if (!dir.exists() || !dir.isDirectory) return@runCatching 0L
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    fun clearCache(context: Context): Long {
        return runCatching {
            val dir = getCacheDir(context)
            if (!dir.exists() || !dir.isDirectory) return@runCatching 0L
            var bytesFreed = 0L
            dir.listFiles()?.forEach { file ->
                val size = if (file.isFile) file.length() else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (file.deleteRecursively()) {
                    bytesFreed += size
                }
            }
            UrlBitmapMemoryCache.clear()
            bytesFreed
        }.getOrDefault(0L)
    }

    private fun hashKey(url: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(url.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

object UrlBitmapMemoryCache {
    private val pinnedCache = mutableMapOf<String, Bitmap>()
    private val cache = object : LruCache<String, Bitmap>(calculateCacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    @Synchronized
    fun get(url: String): Bitmap? = pinnedCache[url] ?: cache.get(url)

    @Synchronized
    fun put(url: String, bitmap: Bitmap, pinned: Boolean = false) {
        if (pinned) {
            pinnedCache[url] = bitmap
            return
        }
        if (pinnedCache[url] == null && cache.get(url) == null) {
            cache.put(url, bitmap)
        }
    }

    @Synchronized
    fun clear() {
        pinnedCache.clear()
        cache.evictAll()
    }

    private fun calculateCacheSizeKb(): Int {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        return (maxMemoryKb / 8).coerceAtLeast(8 * 1024)
    }
}
