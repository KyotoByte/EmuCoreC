package com.sbro.emucorec.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPersistenceContractTest {
    @Test
    fun everyAndroidControlFieldRoundTripsThroughTheSharedJsonCodec() {
        val source = javaSource("core/Ps3CoreConfigRepository.kt")
        val fields = Regex("""val\s+(\w+)\s*:""")
            .findAll(source.substringAfter("data class Ps3CoreConfig(").substringBefore(") {"))
            .map { it.groupValues[1] }
            .toSet()
        val exportBlock = source.substringAfter("internal fun Ps3CoreConfig.toJsonObject")
            .substringBefore("internal fun JSONObject.toPs3CoreConfig")
        val importBlock = source.substringAfter("internal fun JSONObject.toPs3CoreConfig")

        fields.forEach { field ->
            assertTrue("JSON export is missing $field", ".put(\"$field\", $field)" in exportBlock)
            assertTrue("JSON restore is missing $field", Regex("""(?m)^\s*$field\s*=""").containsMatchIn(importBlock))
        }
        assertFalse("Renderer selection belongs to the native RPCS3 tree", "backendRenderer" in fields)
    }

    @Test
    fun globalPerGameAndBackupStorageUseOneAndroidControlCodec() {
        val global = javaSource("core/Ps3CoreConfigRepository.kt")
        val perGame = javaSource("core/Ps3GameSettingsRepository.kt")
        val backup = javaSource("core/SettingsBackupRepository.kt")

        assertTrue("Global config must use the shared encoder", "normalized.toJsonObject()" in global)
        assertTrue("Per-game config must use the shared encoder", "effective.toJsonObject()" in perGame)
        assertTrue("Per-game config must use the shared decoder", "toPs3CoreConfig(base)" in perGame)
        assertTrue("Backup must use the shared Android-control encoder", "ensureDefaultsPersisted().toJsonObject()" in backup)
        assertTrue("Backup must use the shared Android-control decoder", "toPs3CoreConfig(defaults)" in backup)
    }

    @Test
    fun nativeCoreOverridesAreLayeredBackedUpAndAppliedBeforeBoot() {
        val overrides = javaSource("core/Ps3CoreSettingOverrides.kt")
        val backup = javaSource("core/SettingsBackupRepository.kt")
        val runtime = javaSource("core/Ps3Runtime.kt")
        val panel = javaSource("ui/settings/Ps3CoreSettingsSection.kt")

        assertTrue("Global native settings must be persisted", "fun recordGlobal(" in overrides)
        assertTrue("Per-title native settings must be persisted", "fun recordGame(" in overrides)
        assertTrue("Global settings must be applied before game settings", overrides.indexOf("resolvedBase.forEach") < overrides.indexOf("game.forEach"))
        assertTrue("Native settings must be included in backup format 3", ".put(\"nativeCore\"" in backup && "BACKUP_FORMAT_VERSION = 3" in backup)
        assertTrue("Native settings must be restored from backup", "Ps3CoreSettingOverrides.restoreJson" in backup)
        assertTrue("Overrides must be applied before boot", runtime.indexOf("applyForGame(context, titleId)") < runtime.indexOf("Ps3Core.boot(path)"))
        assertTrue("Settings UI must come from the live core tree", "settingsGet(\"\")" in panel)
        assertTrue("Settings UI must write through JNI", "settingsSet(setting.path" in panel)
    }

    private fun javaSource(relative: String): String = appModule()
        .resolve("src/main/java/com/sbro/emucorec")
        .resolve(relative)
        .readText()

    private fun appModule(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        return sequenceOf(workingDirectory, workingDirectory.resolve("app"))
            .firstOrNull { Files.isDirectory(it.resolve("src/main")) }
            ?: error("Unable to locate Android app module from $workingDirectory")
    }
}
