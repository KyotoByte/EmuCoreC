package net.rpcsx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressRepositoryTest {
    @Test
    fun reachingStageMaximumDoesNotCompleteTheInstallation() {
        val updates = mutableListOf<NativeProgress>()
        val id = ProgressRepository.create(updates::add)
        try {
            assertTrue(ProgressRepository.onProgressEvent(id, 100, 100, "Compiling firmware"))
            assertFalse("A full progress bar is not a terminal native event", updates.last().completed)
            assertTrue("The next compilation stage must still receive callbacks", id in ProgressRepository.progress.value)
            assertTrue(ProgressRepository.onProgressEvent(id, 0, 0, "Finalizing"))
        } finally {
            ProgressRepository.cancel(id)
        }
    }

    @Test
    fun listenerReceivesNativeValuesAndTerminalEventCleansUp() {
        val updates = mutableListOf<NativeProgress>()
        val id = ProgressRepository.create(updates::add)

        assertTrue(id in ProgressRepository.progress.value)
        assertTrue(ProgressRepository.onProgressEvent(id, 4, 10, "Extracting"))
        assertEquals(4, ProgressRepository.progress.value.getValue(id).value)
        assertFalse(updates.last().completed)

        assertTrue(ProgressRepository.onProgressCompleted(id, 10, 10, "Done"))
        assertTrue(updates.last().completed)
        assertFalse(id in ProgressRepository.progress.value)
        assertFalse(ProgressRepository.onProgressEvent(id, 11, 10, null))
    }

    @Test
    fun cancellationRejectsFutureNativeWork() {
        val id = ProgressRepository.create()
        ProgressRepository.cancel(id)

        assertFalse(id in ProgressRepository.progress.value)
        assertFalse(ProgressRepository.onProgressEvent(id, 1, 2, null))
    }

    @Test
    fun failurePreservesNativeMessageForTheListener() {
        var failure: NativeProgress? = null
        val id = ProgressRepository.create { update -> if (update.failed) failure = update }

        assertTrue(ProgressRepository.onProgressEvent(id, -1, 0, "Invalid package"))
        assertTrue(failure?.failed == true)
        assertEquals("Invalid package", failure?.message)
        assertFalse(id in ProgressRepository.progress.value)
    }
}
