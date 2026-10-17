package com.sbro.emucorec.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeProgressBusTest {
    @Test
    fun reachingStageMaximumDoesNotCompleteTheInstallation() {
        val updates = mutableListOf<NativeProgress>()
        val id = NativeProgressBus.create(updates::add)
        try {
            assertTrue(NativeProgressBus.onEvent(id, 100, 100, "Compiling firmware", completed = false))
            assertFalse("A full progress bar is not a terminal native event", updates.last().completed)
            assertTrue("The next compilation stage must still receive callbacks", id in NativeProgressBus.progress.value)
            assertTrue(NativeProgressBus.onEvent(id, 0, 0, "Finalizing", completed = false))
        } finally {
            NativeProgressBus.cancel(id)
        }
    }

    @Test
    fun listenerReceivesNativeValuesAndTerminalEventCleansUp() {
        val updates = mutableListOf<NativeProgress>()
        val id = NativeProgressBus.create(updates::add)

        assertTrue(id in NativeProgressBus.progress.value)
        assertTrue(NativeProgressBus.onEvent(id, 4, 10, "Extracting", completed = false))
        assertEquals(4, NativeProgressBus.progress.value.getValue(id).value)
        assertFalse(updates.last().completed)

        assertTrue(NativeProgressBus.onEvent(id, 10, 10, "Done", completed = true))
        assertTrue(updates.last().completed)
        assertFalse(id in NativeProgressBus.progress.value)
        assertFalse(NativeProgressBus.onEvent(id, 11, 10, null, completed = false))
    }

    @Test
    fun cancellationRejectsFutureNativeWork() {
        val id = NativeProgressBus.create()
        NativeProgressBus.cancel(id)

        assertFalse(id in NativeProgressBus.progress.value)
        assertFalse(NativeProgressBus.onEvent(id, 1, 2, null, completed = false))
    }

    @Test
    fun failurePreservesNativeMessageForTheListener() {
        var failure: NativeProgress? = null
        val id = NativeProgressBus.create { update -> if (update.failed) failure = update }

        assertTrue(NativeProgressBus.onEvent(id, -1, 0, "Invalid package", completed = false))
        assertTrue(failure?.failed == true)
        assertEquals("Invalid package", failure?.message)
        assertFalse(id in NativeProgressBus.progress.value)
    }

    @Test
    fun callbackTargetRoutesToTheBus() {
        var seen: NativeProgress? = null
        val id = NativeProgressBus.create { seen = it }

        assertTrue(Ps3CoreCallbacks.onProgressEvent(id, 2, 8, "Working"))
        assertEquals(2L, seen?.value)
        assertTrue(Ps3CoreCallbacks.onProgressCompleted(id, 8, 8, null))
        assertEquals(true, seen?.completed)
    }
}
