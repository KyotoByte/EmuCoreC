package com.sbro.emucorec.core

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SaveStateRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = temporary.root
    private fun file(title: String, name: String): File =
        File(root, "$title/emucorec_slots/$name").apply { parentFile!!.mkdirs() }

    @Test fun onlyTitlesWithNumberedSlotsAreShown() {
        file("BLUS31584", "slot9.SAVESTAT.zst").writeText("state")
        File(root, "OTHER/history.SAVESTAT.zst").apply { parentFile!!.mkdirs(); writeText("history") }
        assertEquals(listOf("BLUS31584"), SaveStateRepository(root).gamesWithSlots())
    }

    @Test fun listUsesTheSameCompressionPreferenceAsTheCore() {
        val compressed = file("BLUS31584", "slot0.SAVESTAT.zst").apply { writeText("zstd") }
        file("BLUS31584", "slot0.SAVESTAT.gz").writeText("gzip")
        file("BLUS31584", "slot9.SAVESTAT").writeText("raw")
        val repository = SaveStateRepository(root)
        assertEquals(listOf(0, 9), repository.listSlots("BLUS31584").map { it.index })
        assertEquals(compressed, repository.stateFile("BLUS31584", 0))
        assertEquals(4L, repository.listSlots("BLUS31584").first().sizeBytes)
    }

    @Test fun deleteRemovesOnlySelectedSlotAndAllItsCompressionVariants() {
        val selected = listOf("", ".gz", ".zst").map {
            file("BLUS31584", "slot2.SAVESTAT$it").apply { writeText("state") }
        }
        val neighbor = file("BLUS31584", "slot3.SAVESTAT.zst").apply { writeText("neighbor") }
        val integrity = file("BLUS31584", "slot2.SAVESTAT.zst.integrity").apply { writeText("digest") }
        val neighborIntegrity = file("BLUS31584", "slot3.SAVESTAT.zst.integrity").apply { writeText("neighbor digest") }
        val other = file("BLUS31193", "slot2.SAVESTAT.zst").apply { writeText("other") }
        assertTrue(SaveStateRepository(root).delete("BLUS31584", 2))
        assertTrue(selected.none(File::exists))
        assertFalse(integrity.exists())
        assertTrue(neighborIntegrity.exists())
        assertTrue(neighbor.exists())
        assertTrue(other.exists())
    }

    @Test fun titlePathsCannotEscapeTheStateDirectory() {
        assertThrows(IllegalArgumentException::class.java) { SaveStateRepository(root).listSlots("../outside") }
        assertThrows(IllegalArgumentException::class.java) { SaveStateRepository(root).stateFile("BLUS31584", 10) }
    }
}
