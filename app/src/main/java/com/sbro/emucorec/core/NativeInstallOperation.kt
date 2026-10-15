package com.sbro.emucorec.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import net.rpcsx.NativeProgress
import net.rpcsx.ProgressRepository

/** A native return value accepts work; only its terminal callback completes it. */
internal suspend fun awaitNativeInstallCompletion(
    start: (Long) -> Boolean,
    onProgress: (NativeProgress) -> Unit = {},
): Boolean = awaitNativeOperationCompletion(start, onProgress, "Installation failed")

internal suspend fun awaitNativeOperationCompletion(
    start: (Long) -> Boolean,
    onProgress: (NativeProgress) -> Unit = {},
    rejectionMessage: String = "Operation failed",
): Boolean = suspendCancellableCoroutine { continuation ->
    val settled = AtomicBoolean(false)
    val progressId = ProgressRepository.create { progress ->
        onProgress(progress)
        if ((progress.failed || progress.completed) && settled.compareAndSet(false, true) && continuation.isActive) {
            continuation.resume(!progress.failed)
        }
    }
    continuation.invokeOnCancellation {
        if (settled.compareAndSet(false, true)) ProgressRepository.cancel(progressId)
    }
    if (!continuation.isActive) return@suspendCancellableCoroutine
    val accepted = runCatching { start(progressId) }.getOrDefault(false)
    if (!accepted && !settled.get()) {
        ProgressRepository.onProgressEvent(progressId, -1, 0, rejectionMessage)
    }
}
