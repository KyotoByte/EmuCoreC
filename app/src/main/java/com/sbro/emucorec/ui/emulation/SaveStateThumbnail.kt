package com.sbro.emucorec.ui.emulation

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import com.sbro.emucorec.core.ps3.Emulator
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal suspend fun captureSaveStateThumbnail(activity: Emulator, destination: File): File? {
    val view = activity.emulationSurface()
    if (!view.holder.surface.isValid || view.width <= 0 || view.height <= 0) return null
    val width = minOf(view.width, 640)
    val bitmap = Bitmap.createBitmap(width, (width.toLong() * view.height / view.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val captured = suspendCancellableCoroutine<Bitmap?> { continuation ->
        try {
            PixelCopy.request(view, bitmap, { result ->
                if (continuation.isActive && result == PixelCopy.SUCCESS) {
                    continuation.resume(bitmap) { _, image, _ -> image?.recycle() }
                }
                else {
                    bitmap.recycle()
                    if (continuation.isActive) continuation.resume(null)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
            continuation.resume(null)
        }
    } ?: return null
    var temporary: File? = null
    return try {
        withContext(Dispatchers.IO) {
            destination.parentFile?.mkdirs()
            val output = File.createTempFile(".preview-", ".jpg", destination.parentFile)
            temporary = output
            try {
                output.outputStream().use { check(captured.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                output
            } catch (error: Exception) { output.delete(); throw error }
        }
    } catch (error: Throwable) { temporary?.delete(); throw error
    } finally { captured.recycle() }
}

internal fun commitSaveStateThumbnail(temporary: File, destination: File) {
    try {
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
