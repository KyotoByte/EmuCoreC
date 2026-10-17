package com.sbro.emucorec.core

import android.content.Context
import android.os.Environment
import com.sbro.emucorec.BuildConfig
import com.sbro.emucorec.data.AppPreferences
import java.io.File

data class Ps3StorageLocation(
    val rootPath: String,
    val ps3Path: String,
    val removable: Boolean,
    val selected: Boolean,
)

data class StorageMigrationResult(
    val sourceRootPath: String,
    val targetRootPath: String,
    val copiedFiles: Int = 0,
    val skippedFiles: Int = 0
)

data class StorageMigrationProgress(
    val sourceRootPath: String,
    val targetRootPath: String,
    val copiedFiles: Int,
    val skippedFiles: Int,
    val totalFiles: Int,
    val currentPath: String?
)

object EmulatorStorage {
    /**
     * Stable location for configuration, logs, patches and transient cache.
 * Only the large PS3 filesystem is allowed to move to removable storage.
     */
    fun runtimeRoot(context: Context): File {
        return (context.getExternalFilesDir(null) ?: context.filesDir).apply { mkdirs() }
    }

    fun storageRoot(context: Context): File {
        val roots = availableStorageRoots(context)
        val selected = AppPreferences(context).ps3StorageRootPath
            ?.let(::File)
            ?.takeIf { configured -> roots.any { it.absolutePath == configured.absolutePath } }
        return selected ?: roots.firstOrNull() ?: context.filesDir
    }

    private fun availableStorageRoots(context: Context): List<File> {
        val roots = linkedMapOf<String, File>()
        context.getExternalFilesDir(null)?.let { roots[it.absolutePath] = it }
        context.getExternalFilesDirs(null).filterNotNull().forEach { root ->
            roots[root.absolutePath] = root
        }
        if (roots.isEmpty()) {
            roots[context.filesDir.absolutePath] = context.filesDir
        }
        return roots.values.toList()
    }

    fun knownStorageRoots(context: Context): List<File> = availableStorageRoots(context)

    fun availableStorageLocations(context: Context): List<Ps3StorageLocation> {
        val selectedRoot = storageRoot(context).absolutePath
        return availableStorageRoots(context).map { root ->
            Ps3StorageLocation(
                rootPath = root.absolutePath,
                ps3Path = File(root, "ps3").absolutePath,
                removable = runCatching { Environment.isExternalStorageRemovable(root) }.getOrDefault(false),
                selected = root.absolutePath == selectedRoot
            )
        }
    }

    fun selectStorageRoot(
        context: Context,
        rootPath: String,
        migrateExistingData: Boolean = false,
        onMigrationProgress: ((StorageMigrationProgress) -> Unit)? = null
    ): StorageMigrationResult {
        val selectedRoot = availableStorageRoots(context).firstOrNull { it.absolutePath == rootPath }
            ?: return StorageMigrationResult(
                sourceRootPath = storageRoot(context).absolutePath,
                targetRootPath = storageRoot(context).absolutePath
            )
        val previousRoot = storageRoot(context)
        migrateLegacyRuntimeData(context, previousRoot)
        val migration = if (migrateExistingData && previousRoot.absolutePath != selectedRoot.absolutePath) {
            migrateRuntimeData(previousRoot, selectedRoot, onMigrationProgress)
        } else {
            StorageMigrationResult(
                sourceRootPath = previousRoot.absolutePath,
                targetRootPath = selectedRoot.absolutePath
            )
        }
        migrateLegacyCacheRoot(context)
        AppPreferences(context).ps3StorageRootPath = selectedRoot.absolutePath
        prepareRuntime(context)
        return migration
    }

    fun ps3Root(context: Context): File {
        val base = storageRoot(context)
        return File(base, "ps3").apply { mkdirs() }
    }

    fun cacheRoot(context: Context): File {
        return File(runtimeRoot(context), "cache").apply { mkdirs() }
    }

    /**
     * Staging area for PS3 install payloads (PUP/PKG/RAP).
     *
     * These are game-sized, so they must follow the user's selected storage
     * root instead of always landing on internal storage. Keeping this separate
     * from [cacheRoot] means small runtime data (logs, shader cache, patches)
     * stays on stable internal storage while bulk transfers go to the SD card.
     */
    fun installStagingRoot(context: Context): File {
        return File(storageRoot(context), "cache/install_cache").apply { mkdirs() }
    }

    /**
     * Synchronized: the app process seeds the runtime on startup, and the
     * emulator boot thread runs it again right before initializing the core.
     * Without the lock the two could copy the bundled Icons tree at the same
     * time.
     */
    @Synchronized
    fun prepareRuntime(context: Context) {
        val runtimeRoot = runtimeRoot(context)
        migrateLegacyRuntimeData(context, storageRoot(context))
        val ps3Root = ps3Root(context)
        val cacheRoot = cacheRoot(context)
        listOf(
            ps3Root,
            cacheRoot,
            File(ps3Root, "config"),
            File(ps3Root, "config/dev_hdd0"),
            File(ps3Root, "config/dev_hdd0/game"),
            File(ps3Root, "config/dev_hdd0/home/00000001/savedata"),
            File(ps3Root, "config/dev_flash"),
            File(ps3Root, "config/games"),
            File(runtimeRoot, "shaderlog"),
            File(runtimeRoot, "texturelog"),
            File(runtimeRoot, "patch"),
            File(runtimeRoot, "config"),
            File(cacheRoot, "shaders"),
            File(cacheRoot, "logs")
        ).forEach { directory ->
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }

        installBundledResources(context, ps3Root)

        purgeOrphanedInstallStaging(context)
    }

    private fun installBundledResources(context: Context, ps3Root: File) {
        // The Icons tree is hundreds of PNGs. This used to recopy all of them on
        // every call -- app start, emulator start, core init -- which on a cold
        // first launch (running on the UI thread back then) alone exceeded the
        // input-dispatch timeout. Seeded once per app version is enough.
        val marker = File(ps3Root, "config/Icons/.seeded-version")
        val version = BuildConfig.VERSION_CODE.toString()
        if (runCatching { marker.readText().trim() }.getOrNull() == version) {
            return
        }

        copyAssetTree(context, "Icons", File(ps3Root, "config/Icons"))

        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(version)
        }
    }

    private fun copyAssetTree(context: Context, assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            destination.mkdirs()
            children.forEach { child ->
                copyAssetTree(context, "$assetPath/$child", File(destination, child))
            }
            return
        }

        destination.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /**
     * Removes install payloads left behind by earlier versions.
     *
     * Staging used to live on internal storage and was never cleaned up, so a
     * single game install could strand several GB there. Both the legacy
     * internal location and the current one are swept, since a staged file only
     * ever needs to outlive the install that created it.
     */
    private fun purgeOrphanedInstallStaging(context: Context) {
        val stagingDirs = buildSet {
            add(File(runtimeRoot(context), "cache/install_cache"))
            add(File(storageRoot(context), "cache/install_cache"))
        }

        stagingDirs.forEach { dir ->
            runCatching {
                if (!dir.isDirectory) return@runCatching
                dir.listFiles()?.forEach { staged ->
                    if (staged.isFile) staged.delete() else staged.deleteRecursively()
                }
            }
        }
    }

    fun hdd0GameRoot(context: Context): File =
        File(ps3Root(context), "config/dev_hdd0/game").apply { mkdirs() }

    fun discGamesRoot(context: Context): File =
        File(ps3Root(context), "config/games").apply { mkdirs() }

    fun hdd0DiscRoot(context: Context): File =
        File(ps3Root(context), "config/dev_hdd0/disc").apply { mkdirs() }

    /** Summary of a cache clear, in bytes freed and files removed. */
    data class CacheClearResult(
        val bytesFreed: Long,
        val filesRemoved: Int
    )

    /**
     * Reports the size of caches that [clearCaches] would remove.
     */
    fun cacheSizeBytes(context: Context): Long =
        clearableCacheDirs(context).sumOf { dir ->
            runCatching {
                if (!dir.isDirectory) 0L
                else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }.getOrDefault(0L)
        }

    /**
     * Deletes regenerable caches only.
     *
     * Shader/texture caches, logs and install staging are all rebuilt on demand,
     * so removing them is always safe. Saves, installed games, firmware,
     * trophies, settings and GPU drivers are deliberately untouched.
     */
    fun clearCaches(context: Context): CacheClearResult {
        var bytesFreed = 0L
        var filesRemoved = 0

        clearableCacheDirs(context).forEach { dir ->
            runCatching {
                if (!dir.isDirectory) return@runCatching
                dir.listFiles()?.forEach { entry ->
                    val size = if (entry.isFile) {
                        entry.length()
                    } else {
                        entry.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    }
                    val count = if (entry.isFile) {
                        1
                    } else {
                        entry.walkTopDown().count { it.isFile }
                    }
                    val deleted = if (entry.isFile) entry.delete() else entry.deleteRecursively()
                    if (deleted) {
                        bytesFreed += size
                        filesRemoved += count
                    }
                }
            }
        }

        // Recreate the directory skeleton so the core does not have to.
        prepareRuntime(context)

        return CacheClearResult(bytesFreed = bytesFreed, filesRemoved = filesRemoved)
    }

    /**
     * Cache directories that are safe to delete.
     *
     * Both storage roots are covered because the selected root may have changed
     * since the cache was written.
     */
    private fun clearableCacheDirs(context: Context): List<File> {
        val runtimeRoot = runtimeRoot(context)
        val storageRoot = storageRoot(context)
        return buildSet {
            add(File(runtimeRoot, "cache"))
            add(File(storageRoot, "cache"))
            add(File(runtimeRoot, "shaderlog"))
            add(File(runtimeRoot, "texturelog"))
            add(context.cacheDir)
            context.externalCacheDir?.let(::add)
        }.toList()
    }

    fun hdd0SaveDataRoot(context: Context, userId: String? = null): File {
        val userSegment = userId?.takeIf(String::isNotBlank) ?: "00000001"
        val relativePath = "config/dev_hdd0/home/$userSegment/savedata"
        return File(ps3Root(context), relativePath).apply { mkdirs() }
    }

    fun hasInstalledFirmware(context: Context): Boolean {
        return systemMenuExecutable(context).isFile
    }

    fun systemMenuExecutable(context: Context): File =
        File(ps3Root(context), "config/dev_flash/vsh/module/vsh.self")

    fun iconPath(context: Context, titleId: String): File {
        val candidates = listOf(
            File(hdd0GameRoot(context), "$titleId/ICON0.PNG"),
            File(hdd0GameRoot(context), "$titleId/PS3_GAME/ICON0.PNG"),
            File(discGamesRoot(context), "$titleId/PS3_GAME/ICON0.PNG"),
            File(discGamesRoot(context), "$titleId/ICON0.PNG"),
            File(hdd0DiscRoot(context), "$titleId/PS3_GAME/ICON0.PNG")
        )
        return candidates.firstOrNull(File::exists) ?: candidates.first()
    }

    fun paramSfoPath(context: Context, titleId: String): File {
        val candidates = listOf(
            File(hdd0GameRoot(context), "$titleId/PARAM.SFO"),
            File(hdd0GameRoot(context), "$titleId/PS3_GAME/PARAM.SFO"),
            File(discGamesRoot(context), "$titleId/PS3_GAME/PARAM.SFO"),
            File(discGamesRoot(context), "$titleId/PARAM.SFO"),
            File(hdd0DiscRoot(context), "$titleId/PS3_GAME/PARAM.SFO")
        )
        return candidates.firstOrNull(File::exists) ?: candidates.first()
    }

    private fun migrateRuntimeData(
        sourceRoot: File,
        targetRoot: File,
        onProgress: ((StorageMigrationProgress) -> Unit)?
    ): StorageMigrationResult {
        if (!sourceRoot.exists() || sourceRoot.absolutePath == targetRoot.absolutePath) {
            return StorageMigrationResult(sourceRoot.absolutePath, targetRoot.absolutePath)
        }
        targetRoot.mkdirs()
        val migrationItems = listOf("ps3")
        val totalFiles = migrationItems.sumOf { name -> File(sourceRoot, name).countFiles() }
        onProgress?.invoke(
            StorageMigrationProgress(
                sourceRootPath = sourceRoot.absolutePath,
                targetRootPath = targetRoot.absolutePath,
                copiedFiles = 0,
                skippedFiles = 0,
                totalFiles = totalFiles,
                currentPath = null
            )
        )
        var copied = 0
        var skipped = 0
        migrationItems.forEach { name ->
            val source = File(sourceRoot, name)
            if (source.exists()) {
                copyMissing(source, File(targetRoot, name)) { copiedDelta, skippedDelta, current ->
                    copied += copiedDelta
                    skipped += skippedDelta
                    onProgress?.invoke(
                        StorageMigrationProgress(
                            sourceRootPath = sourceRoot.absolutePath,
                            targetRootPath = targetRoot.absolutePath,
                            copiedFiles = copied,
                            skippedFiles = skipped,
                            totalFiles = totalFiles,
                            currentPath = current.relativeToOrSelf(sourceRoot).path
                        )
                    )
                }
            }
        }
        return StorageMigrationResult(
            sourceRootPath = sourceRoot.absolutePath,
            targetRootPath = targetRoot.absolutePath,
            copiedFiles = copied,
            skippedFiles = skipped
        )
    }

    private fun copyMissing(
        source: File,
        target: File,
        onFileVisited: (copiedDelta: Int, skippedDelta: Int, current: File) -> Unit
    ): Pair<Int, Int> {
        if (source.isDirectory) {
            if (!target.exists()) {
                target.mkdirs()
            }
            var copied = 0
            var skipped = 0
            source.listFiles().orEmpty().forEach { child ->
                val result = copyMissing(child, File(target, child.name), onFileVisited)
                copied += result.first
                skipped += result.second
            }
            return copied to skipped
        }
        if (target.exists()) {
            onFileVisited(0, 1, source)
            return 0 to 1
        }
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = false)
        onFileVisited(1, 0, source)
        return 1 to 0
    }

    private fun File.countFiles(): Int {
        if (!exists()) return 0
        if (isFile) return 1
        return listFiles().orEmpty().sumOf { it.countFiles() }
    }

    private fun migrateLegacyCacheRoot(context: Context) {
        val legacyBase = context.externalCacheDir ?: context.cacheDir
        val legacyCache = File(legacyBase, "ps3_cache")
        val targetCache = cacheRoot(context)
        if (legacyCache.exists() && legacyCache.absolutePath != targetCache.absolutePath) {
            copyMissing(legacyCache, targetCache) { _, _, _ -> }
        }
    }

    private fun migrateLegacyRuntimeData(context: Context, legacyRoot: File) {
        val targetRoot = runtimeRoot(context)
        if (!legacyRoot.exists() || legacyRoot.absolutePath == targetRoot.absolutePath) return
        listOf("cache", "patch", "shaderlog", "texturelog", "config.yml", "config", "play_time.json")
            .forEach { name ->
                val source = File(legacyRoot, name)
                if (source.exists()) {
                    copyNewer(source, File(targetRoot, name))
                }
            }
    }

    private fun copyNewer(source: File, target: File) {
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles().orEmpty().forEach { child ->
                copyNewer(child, File(target, child.name))
            }
            return
        }
        if (target.exists() && target.lastModified() >= source.lastModified()) return
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
    }

}
