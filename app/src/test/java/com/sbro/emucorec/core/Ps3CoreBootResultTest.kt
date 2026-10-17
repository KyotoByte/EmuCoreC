package com.sbro.emucorec.core

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

class Ps3CoreBootResultTest {
    @Test
    fun everyNativeBootResultIsDecodedWithoutChangingItsMeaning() {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        val header = sequenceOf(workingDirectory, workingDirectory.parent)
            .map { it.resolve("core/rpcs3/Emu/System.h") }
            .first(Files::isRegularFile)
        val declaration = Files.readString(header)
            .substringAfter("enum class game_boot_result : u32")
            .substringAfter("{")
            .substringBefore("};")
        val names = declaration.split(',').map(String::trim).filter(String::isNotBlank)

        names.forEachIndexed { code, nativeName ->
            assertEquals("Boot result $code must retain its native meaning",
                nativeName.replace("_", "").lowercase(),
                BootResult.fromInt(code).name.lowercase())
        }
    }

    @Test
    fun unknownNativeBootResultsReportAnErrorInsteadOfCrashing() {
        listOf(-1, Int.MIN_VALUE, 20, Int.MAX_VALUE).forEach { code ->
            assertEquals(BootResult.GenericError, BootResult.fromInt(code))
        }
    }
}
