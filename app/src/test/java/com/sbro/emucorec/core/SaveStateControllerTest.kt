package com.sbro.emucorec.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import net.rpcsx.ProgressRepository
import org.junit.Assert.*
import org.junit.Test

class SaveStateControllerTest {
    private class Backend : SaveStateBackend {
        var entries = emptyList<SaveStateSlot>()
        var requestId = 0L
        var requestedSlot = -1
        var loadCount = 0
        var accepted = true
        override fun slots() = entries
        override fun save(slot: Int, progressId: Long): Boolean {
            requestedSlot = slot
            requestId = progressId
            return accepted
        }
        override fun load(slot: Int, progressId: Long): Boolean {
            loadCount++
            requestedSlot = slot
            requestId = progressId
            return accepted
        }
    }

    @Test
    fun saveWaitsForCommittedFileAndRestoredGame() = runBlocking {
        val backend = Backend()
        val controller = SaveStateController(backend)
        val save = async(start = CoroutineStart.UNDISPATCHED) { controller.save(9) }
        try {
            assertEquals(9, backend.requestedSlot)
            assertTrue(controller.busy.value)
            assertFalse(save.isCompleted)
            ProgressRepository.onProgressEvent(backend.requestId, 1, 1, "Writing")
            assertFalse(save.isCompleted)
            backend.entries = listOf(SaveStateSlot(9, 123, 4096))
            ProgressRepository.onProgressCompleted(backend.requestId, 1, 1, "Saved")
            assertEquals(SaveStateOutcome.Success, save.await())
            assertEquals(backend.entries, controller.slots.value)
            assertFalse(controller.busy.value)
        } finally { save.cancel() }
    }

    @Test
    fun failedSaveKeepsTheExistingSlot() = runBlocking {
        val backend = Backend().apply { entries = listOf(SaveStateSlot(2, 123, 4096)) }
        val controller = SaveStateController(backend)
        controller.refresh()
        val save = async(start = CoroutineStart.UNDISPATCHED) { controller.save(2) }
        ProgressRepository.onProgressEvent(backend.requestId, -1, 0, "Write failed")
        assertEquals(SaveStateOutcome.Failed, save.await())
        assertEquals(backend.entries, controller.slots.value)
        assertFalse(controller.busy.value)
    }

    @Test
    fun emptySlotDoesNotStartATeardown() = runBlocking {
        val backend = Backend()
        assertEquals(SaveStateOutcome.Empty, SaveStateController(backend).load(3))
        assertEquals(0, backend.loadCount)
    }

    @Test
    fun requestsCannotOverlap() = runBlocking {
        val backend = Backend()
        val controller = SaveStateController(backend)
        val first = async(start = CoroutineStart.UNDISPATCHED) { controller.save(0) }
        try {
            val firstId = backend.requestId
            assertEquals(SaveStateOutcome.Busy, controller.save(1))
            assertEquals(firstId, backend.requestId)
            ProgressRepository.onProgressCompleted(firstId, 1, 1, "Saved")
            assertEquals(SaveStateOutcome.Success, first.await())
        } finally { first.cancel() }
    }

    @Test
    fun rejectedRequestClearsBusyAndProgress() = runBlocking {
        val backend = Backend().apply { accepted = false }
        val controller = SaveStateController(backend)
        assertEquals(SaveStateOutcome.Failed, controller.save(0))
        assertFalse(controller.busy.value)
        assertFalse(backend.requestId in ProgressRepository.progress.value)
    }

    @Test
    fun cancellingWaitDoesNotAcceptLateSuccess() = runBlocking {
        val backend = Backend()
        val controller = SaveStateController(backend)
        val save = async(start = CoroutineStart.UNDISPATCHED) { controller.save(0) }
        save.cancel()
        save.join()
        assertFalse(controller.busy.value)
        assertFalse(ProgressRepository.onProgressCompleted(backend.requestId, 1, 1, "Saved"))
    }
}
