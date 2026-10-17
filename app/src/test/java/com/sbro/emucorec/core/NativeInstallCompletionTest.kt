package com.sbro.emucorec.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
            NativeProgressBus.onEvent(progressId, -1, 0, "Compilation failed", completed = false)
            assertFalse(operation.await())
        } finally {
            operation.cancel()
            NativeProgressBus.cancel(progressId)
        }
    }

    @Test
    fun fullStageProgressWaitsForCleanupAndExplicitSuccess() = runBlocking {
        var progressId = 0L
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            awaitNativeInstallCompletion({ id -> progressId = id; true })
        }
        try {
            NativeProgressBus.onEvent(progressId, 100, 100, "Compiling", completed = false)
            assertFalse(operation.isCompleted)
            NativeProgressBus.onEvent(progressId, 0, 0, "Finalizing", completed = false)
            assertFalse(operation.isCompleted)
            NativeProgressBus.onEvent(progressId, 1, 1, "Done", completed = true)
            assertTrue(operation.await())
            assertFalse(progressId in NativeProgressBus.progress.value)
        } finally {
            operation.cancel()
            NativeProgressBus.cancel(progressId)
        }
    }

    @Test
    fun nativeRejectionCompletesWithFailure() = runBlocking {
        var progressId = 0L
        assertFalse(awaitNativeInstallCompletion({ id -> progressId = id; false }))
        assertFalse(progressId in NativeProgressBus.progress.value)
    }

    @Test
    fun synchronousCompletionDoesNotLoseTheResult() = runBlocking {
        assertTrue(awaitNativeInstallCompletion({ id ->
            NativeProgressBus.onEvent(id, 1, 1, "Done", completed = true)
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
        assertFalse(progressId in NativeProgressBus.progress.value)
        assertFalse(NativeProgressBus.onEvent(progressId, 1, 1, "Done", completed = true))
    }
}
