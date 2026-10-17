package com.sbro.emucorec.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseOptimizationContractTest {
    @Test
    fun releaseUsesR8AndResourceShrinkingWithoutBreakingNativeLookups() {
        val module = appModule()
        val gradle = module.resolve("build.gradle.kts").readText()
        val rules = module.resolve("proguard-rules.pro").readText()
        val resources = module.resolve("src/main/res/raw/keep.xml").readText()

        assertTrue("Release minification must stay enabled", "isMinifyEnabled = true" in gradle)
        assertTrue("Release resource shrinking must stay enabled", "isShrinkResources = true" in gradle)
        assertTrue("Use the optimized Android defaults", "proguard-android-optimize.txt" in gradle)
        listOf(
            "com.sbro.emucorec.core.Ps3Core",
            "com.sbro.emucorec.core.Ps3CoreCallbacks",
            "com.sbro.emucorec.feedback.FeedbackUploadWorker",
            "com.sbro.emucorec.data.drive.DriveScheduleWorker",
            "com.sbro.emucorec.data.drive.DriveBackupWorker",
            "org.libsdl.app.**",
        ).forEach { nativeName ->
            assertTrue("Missing R8 keep rule for $nativeName", nativeName in rules)
        }
        assertTrue("Dynamic setting labels must survive resource shrinking", "@string/core_label_*" in resources)
        assertTrue("Dynamic setting help must survive resource shrinking", "@string/core_help_*" in resources)
    }

    private fun appModule(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        return sequenceOf(workingDirectory, workingDirectory.resolve("app"))
            .firstOrNull { Files.isDirectory(it.resolve("src/main")) }
            ?: error("Unable to locate Android app module from $workingDirectory")
    }
}
