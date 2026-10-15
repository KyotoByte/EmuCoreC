package com.sbro.emucorec.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import net.rpcsx.ProgressRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeInstallCompletionTest {
    @Test
    fun acceptedNativeWorkDoesNotCompleteBeforeItsCallback() = runBlocking {
        var progressId = 0L
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            awaitNativeInstallCompletion({ id -> progressId = id; true })
        }
        try {
            assertTrue(progressId > 0)
            assertFalse("Accepting a queued install must not report success", operation.isCompleted)
            ProgressRepository.onProgressEvent(progressId, -1, 0, "Compilation failed")
            assertFalse(operation.await())
        } finally {
            operation.cancel()
            ProgressRepository.cancel(progressId)
        }
    }

    @Test
    fun fullStageProgressWaitsForCleanupAndExplicitSuccess() = runBlocking {
        var progressId = 0L
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            awaitNativeInstallCompletion({ id -> progressId = id; true })
        }
        try {
            ProgressRepository.onProgressEvent(progressId, 100, 100, "Compiling")
            assertFalse(operation.isCompleted)
            ProgressRepository.onProgressEvent(progressId, 0, 0, "Finalizing")
            assertFalse(operation.isCompleted)
            ProgressRepository.onProgressCompleted(progressId, 1, 1, "Done")
            assertTrue(operation.await())
            assertFalse(progressId in ProgressRepository.progress.value)
        } finally {
            operation.cancel()
            ProgressRepository.cancel(progressId)
        }
    }

    @Test
    fun nativeRejectionCompletesWithFailure() = runBlocking {
        var progressId = 0L
        assertFalse(awaitNativeInstallCompletion({ id -> progressId = id; false }))
        assertFalse(progressId in ProgressRepository.progress.value)
    }

    @Test
    fun synchronousCompletionDoesNotLoseTheResult() = runBlocking {
        assertTrue(awaitNativeInstallCompletion({ id ->
            ProgressRepository.onProgressCompleted(id, 1, 1, "Done")
            true
        }))
    }

    @Test
    fun cancellingAnInstallRejectsLateNativeCallbacks() = runBlocking {
        var progressId = 0L
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            awaitNativeInstallCompletion({ id -> progressId = id; true })
        }
        operation.cancel()
        operation.join()
        assertFalse(progressId in ProgressRepository.progress.value)
        assertFalse(ProgressRepository.onProgressCompleted(progressId, 1, 1, "Done"))
    }
}
