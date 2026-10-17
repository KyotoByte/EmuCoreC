package com.sbro.emucorec.core

import android.content.Context
import com.sbro.emucorec.data.InstalledPs3Game
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONObject

object Ps3IsoParser {
    private val TITLE_ID_REGEX = Regex("([A-Za-z]{4}[0-9]{5})", RegexOption.IGNORE_CASE)

    fun isIsoImage(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in listOf("iso", "bin", "img", "chd")
    }

    fun parse(context: Context, file: File): InstalledPs3Game? {
        if (!file.isFile) return null

        if (isChdImage(file)) return parseChd(context, file)

        val sfoDataAndIcon = extractSfoAndIconFromIso(context, file)
        val sfo = sfoDataAndIcon?.first
        val iconPath = sfoDataAndIcon?.second

        val titleId = sfo?.titleId?.takeIf(String::isNotBlank)
            ?: extractTitleIdFromFilename(file.name)
            ?: generateFallbackTitleId(file.name)

        val title = sfo?.title?.takeIf(String::isNotBlank)
            ?: cleanTitleFromFilename(file.name)

        return InstalledPs3Game(
            titleId = titleId.uppercase(),
            title = title,
            contentId = sfo?.contentId,
            saveDataId = sfo?.saveDataId ?: titleId.uppercase(),
            version = sfo?.version ?: "01.00",
            category = sfo?.category ?: "DG",
            iconPath = iconPath,
            catalogCoverUrl = null,
            installPath = file.absolutePath
        )
    }

    private fun isChdImage(file: File): Boolean {
        if (file.extension.equals("chd", ignoreCase = true)) return true
        return runCatching {
            file.inputStream().use { input ->
                val magic = ByteArray(8)
                input.read(magic) == magic.size && magic.contentEquals("MComprHD".toByteArray(Charsets.US_ASCII))
            }
        }.getOrDefault(false)
    }

    private fun parseChd(context: Context, file: File): InstalledPs3Game? = runCatching {
        if (!Ps3Runtime.ensureInitialized(context)) return null
        val identity = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val icon = File(File(context.cacheDir, "iso_icons").apply { mkdirs() }, "$key.png")
        val metadata = JSONObject(Ps3Core.instance.probeDiscInfo(file.absolutePath, icon.absolutePath))
        val titleId = metadata.optString("titleId").takeIf(String::isNotBlank) ?: return null
        InstalledPs3Game(
            titleId = titleId.uppercase(),
            title = metadata.optString("title").ifBlank { cleanTitleFromFilename(file.name) },
            contentId = metadata.optString("contentId").takeIf(String::isNotBlank),
            saveDataId = metadata.optString("saveDataId").ifBlank { titleId.uppercase() },
            version = metadata.optString("version").ifBlank { "01.00" },
            category = metadata.optString("category").ifBlank { "DG" },
            iconPath = icon.takeIf { metadata.optBoolean("icon") && it.isFile && it.length() > 0 }?.absolutePath,
            catalogCoverUrl = null,
            installPath = file.absolutePath,
        )
    }.getOrNull()

    fun extractTitleIdFromFilename(filename: String): String? {
        return TITLE_ID_REGEX.find(filename)?.value?.uppercase()
    }

    fun cleanTitleFromFilename(filename: String): String {
        val withoutExt = filename.substringBeforeLast('.')
        val cleaned = withoutExt
            .replace(TITLE_ID_REGEX, "")
            .replace(Regex("\\[.*?\\]|\\(.*?\\)"), "")
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifBlank { withoutExt }
    }

    private fun generateFallbackTitleId(filename: String): String {
        val hash = (filename.hashCode().toLong() and 0xFFFFFFFFL).toString().padStart(5, '0').takeLast(5)
        return "DISC$hash"
    }

    private fun extractSfoAndIconFromIso(context: Context, file: File): Pair<Ps3SfoData?, String?>? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                // Try ISO9660 Directory Traversal
                val isoResult = parseIso9660(context, raf, file.nameWithoutExtension)
                if (isoResult != null) return@use isoResult

                // Fallback: Scan first 64MB for \0PSF magic
                val sfoBytes = scanForSfoBytes(raf)
                if (sfoBytes != null) {
                    val parsed = Ps3SfoParser.parse(sfoBytes)
                    return@use Pair(parsed, null)
                }

                null
            }
        }.getOrNull()
    }

    private fun parseIso9660(context: Context, raf: RandomAccessFile, baseName: String): Pair<Ps3SfoData?, String?>? {
        val pvdOffset = 16L * 2048L
        if (raf.length() < pvdOffset + 2048) return null

        raf.seek(pvdOffset)
        val pvd = ByteArray(2048)
        raf.readFully(pvd)

        // Check for Standard Identifier "CD001"
        if (pvd[1].toInt() != 0x43 || pvd[2].toInt() != 0x44 || pvd[3].toInt() != 0x30 ||
            pvd[4].toInt() != 0x30 || pvd[5].toInt() != 0x31) {
            return null
        }

        // Root directory record is at offset 156 in PVD
        val rootRecord = ByteBuffer.wrap(pvd, 156, 34).order(ByteOrder.LITTLE_ENDIAN)
        val rootRecordLen = rootRecord.get().toInt() and 0xFF
        if (rootRecordLen < 34) return null
        rootRecord.get() // ext attr
        val rootLba = rootRecord.getInt()
        rootRecord.getInt() // be LBA
        val rootDataLen = rootRecord.getInt()

        val rootDirEntries = readDirectoryRecords(raf, rootLba.toLong() * 2048L, rootDataLen.toLong())
        val ps3GameEntry = rootDirEntries.firstOrNull { it.name.equals("PS3_GAME", ignoreCase = true) }
            ?: return null

        val ps3GameDirEntries = readDirectoryRecords(raf, ps3GameEntry.lba.toLong() * 2048L, ps3GameEntry.dataLength.toLong())

        var sfoData: Ps3SfoData? = null
        var iconPath: String? = null

        val sfoEntry = ps3GameDirEntries.firstOrNull { it.name.startsWith("PARAM.SFO", ignoreCase = true) }
        if (sfoEntry != null && sfoEntry.dataLength in 1..1048576) {
            raf.seek(sfoEntry.lba.toLong() * 2048L)
            val sfoBytes = ByteArray(sfoEntry.dataLength)
            raf.readFully(sfoBytes)
            sfoData = Ps3SfoParser.parse(sfoBytes)
        }

        val iconEntry = ps3GameDirEntries.firstOrNull { it.name.startsWith("ICON0.PNG", ignoreCase = true) }
        if (iconEntry != null && iconEntry.dataLength in 1..10485760) {
            val titleId = sfoData?.titleId?.ifBlank { null } ?: baseName
            val iconsDir = File(context.cacheDir, "iso_icons").apply { if (!exists()) mkdirs() }
            val iconFile = File(iconsDir, "$titleId.png")
            raf.seek(iconEntry.lba.toLong() * 2048L)
            val iconBytes = ByteArray(iconEntry.dataLength)
            raf.readFully(iconBytes)
            iconFile.writeBytes(iconBytes)
            if (iconFile.isFile && iconFile.length() > 0) {
                iconPath = iconFile.absolutePath
            }
        }

        return if (sfoData != null || iconPath != null) Pair(sfoData, iconPath) else null
    }

    private data class DirectoryRecord(val name: String, val lba: Int, val dataLength: Int, val isDirectory: Boolean)

    private fun readDirectoryRecords(raf: RandomAccessFile, offset: Long, length: Long): List<DirectoryRecord> {
        val records = mutableListOf<DirectoryRecord>()
        val maxLen = length.coerceAtMost(raf.length() - offset).toInt()
        if (maxLen <= 0) return records

        raf.seek(offset)
        val data = ByteArray(maxLen)
        raf.readFully(data)

        var pos = 0
        while (pos < maxLen) {
            val recordLen = data[pos].toInt() and 0xFF
            if (recordLen == 0) {
                val nextSector = ((pos / 2048) + 1) * 2048
                if (nextSector <= pos || nextSector >= maxLen) break
                pos = nextSector
                continue
            }

            if (pos + recordLen > maxLen) break

            val buf = ByteBuffer.wrap(data, pos, recordLen).order(ByteOrder.LITTLE_ENDIAN)
            buf.get() // len
            buf.get() // ext attr len
            val lba = buf.getInt()
            buf.getInt() // be lba
            val dataLen = buf.getInt()
            buf.getInt() // be dataLen
            buf.position(buf.position() + 7) // date
            val flags = buf.get().toInt() and 0xFF
            val isDir = (flags and 0x02) != 0
            buf.get() // unit size
            buf.get() // interleave
            buf.getInt() // vol seq num
            val nameLen = buf.get().toInt() and 0xFF

            if (nameLen > 0 && buf.position() + nameLen <= pos + recordLen) {
                val nameBytes = ByteArray(nameLen)
                buf.get(nameBytes)
                val rawName = String(nameBytes, Charsets.ISO_8859_1)
                val name = if (rawName.contains(';')) rawName.substringBefore(';') else rawName
                if (name.isNotEmpty() && name != "\u0000" && name != "\u0001") {
                    records.add(DirectoryRecord(name, lba, dataLen, isDir))
                }
            }

            pos += recordLen
        }

        return records
    }

    fun extractTrophyPackages(context: Context, file: File): List<File> {
        if (!file.isFile) return emptyList()
        val titleId = extractTitleIdFromFilename(file.name) ?: generateFallbackTitleId(file.name)
        val cacheRoot = File(context.cacheDir, "iso_trophies/$titleId")
        if (cacheRoot.isDirectory && cacheRoot.walkTopDown().any { it.isFile && it.name.equals("TROPHY.TRP", ignoreCase = true) }) {
            return cacheRoot.walkTopDown()
                .filter { it.isFile && it.name.equals("TROPHY.TRP", ignoreCase = true) }
                .mapNotNull { it.parentFile }
                .distinctBy { it.absolutePath }
                .toList()
        }

        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val pvdOffset = 16L * 2048L
                if (raf.length() < pvdOffset + 2048) return@use emptyList()

                raf.seek(pvdOffset)
                val pvd = ByteArray(2048)
                raf.readFully(pvd)

                if (pvd[1].toInt() != 0x43 || pvd[2].toInt() != 0x44 || pvd[3].toInt() != 0x30 ||
                    pvd[4].toInt() != 0x30 || pvd[5].toInt() != 0x31) {
                    return@use emptyList()
                }

                val rootRecord = ByteBuffer.wrap(pvd, 156, 34).order(ByteOrder.LITTLE_ENDIAN)
                val rootRecordLen = rootRecord.get().toInt() and 0xFF
                if (rootRecordLen < 34) return@use emptyList()
                rootRecord.get()
                val rootLba = rootRecord.getInt()
                rootRecord.getInt()
                val rootDataLen = rootRecord.getInt()

                val rootDirEntries = readDirectoryRecords(raf, rootLba.toLong() * 2048L, rootDataLen.toLong())
                val ps3GameEntry = rootDirEntries.firstOrNull { it.name.equals("PS3_GAME", ignoreCase = true) }
                    ?: return@use emptyList()

                val ps3GameDirEntries = readDirectoryRecords(raf, ps3GameEntry.lba.toLong() * 2048L, ps3GameEntry.dataLength.toLong())
                val tropDirEntry = ps3GameDirEntries.firstOrNull { it.name.equals("TROPDIR", ignoreCase = true) }
                    ?: return@use emptyList()

                val tropDirEntries = readDirectoryRecords(raf, tropDirEntry.lba.toLong() * 2048L, tropDirEntry.dataLength.toLong())
                val results = mutableListOf<File>()

                tropDirEntries.filter { it.isDirectory }.forEach { commEntry ->
                    val npEntries = readDirectoryRecords(raf, commEntry.lba.toLong() * 2048L, commEntry.dataLength.toLong())
                    val trpEntry = npEntries.firstOrNull { it.name.startsWith("TROPHY.TRP", ignoreCase = true) || it.name.endsWith(".TRP", ignoreCase = true) }
                    if (trpEntry != null && trpEntry.dataLength in 1..20971520) {
                        val outDir = File(cacheRoot, commEntry.name).apply { mkdirs() }
                        val outFile = File(outDir, "TROPHY.TRP")
                        raf.seek(trpEntry.lba.toLong() * 2048L)
                        val bytes = ByteArray(trpEntry.dataLength)
                        raf.readFully(bytes)
                        outFile.writeBytes(bytes)
                        if (outFile.isFile) {
                            results.add(outDir)
                        }
                    }
                }

                results
            }
        }.getOrDefault(emptyList())
    }

    internal fun scanForSfoBytes(raf: RandomAccessFile): ByteArray? {
        val maxScan = 64L * 1024L * 1024L // 64 MB
        val limit = raf.length().coerceAtMost(maxScan).toInt()
        if (limit < 20) return null

        val bufferSize = 2 * 1024 * 1024 // 2 MB
        val buffer = ByteArray(bufferSize)
        var offset = 0L

        while (offset < limit) {
            val toRead = bufferSize.coerceAtMost((limit - offset).toInt())
            raf.seek(offset)
            raf.readFully(buffer, 0, toRead)

            for (i in 0..toRead - 20) {
                if (buffer[i] == 0.toByte() && buffer[i + 1] == 0x50.toByte() &&
                    buffer[i + 2] == 0x53.toByte() && buffer[i + 3] == 0x46.toByte() &&
                    buffer[i + 4] == 0x01.toByte() && buffer[i + 5] == 0x01.toByte()) {

                    val sfoLen = 65536
                    val sfoBytes = ByteArray(sfoLen)
                    raf.seek(offset + i)
                    val read = raf.read(sfoBytes)
                    if (read >= 20) {
                        return sfoBytes.copyOf(read)
                    }
                }
            }
            if (offset + toRead >= limit) break
            offset += (toRead - 1024).coerceAtLeast(1)
        }
        return null
    }
}
