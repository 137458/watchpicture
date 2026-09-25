package com.watchpicture.app.archive

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale

/**
 * High-performance, streaming archive manager powered by Zip4j.
 * Strictly avoids extracting archives to disk; delivers entries as memory streams.
 */
class ZipArchiveManager(
    val sevenZManager: SevenZArchiveManager = SevenZArchiveManager(),
    val handlePool: ArchiveHandlePool = ArchiveHandlePool()
) {

    companion object {
        private val GBK_CHARSET = Charset.forName("GBK")
        private val SUPPORTED_IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif",
            "jfif", "pjpeg", "pjp", "tiff", "tif", "ico", "svg"
        )
        private val SUPPORTED_VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "flv", "wmv", "mpg", "mpeg", "ts"
        )
        private val naturalOrderComparator = NaturalOrderComparator()

        fun isImageFile(fileName: String): Boolean {
            val name = fileName.lowercase(Locale.ROOT)
            val ext = name.substringAfterLast('.', "")
            return ext in SUPPORTED_IMAGE_EXTENSIONS
        }

        /**
         * 是否为可播放的视频文件。视频会被纳入图包条目列表，但无法按图片解码显示，
         * 缩略图与播放分别走视频帧解码与系统播放器。
         */
        fun isVideoFile(fileName: String): Boolean {
            val name = fileName.lowercase(Locale.ROOT)
            val ext = name.substringAfterLast('.', "")
            return ext in SUPPORTED_VIDEO_EXTENSIONS
        }

        /**
         * 图包条目判定：可在图包中列出与浏览的媒体文件（图片或视频）。
         */
        fun isMediaFile(fileName: String): Boolean = isImageFile(fileName) || isVideoFile(fileName)

        fun isIgnoredFile(fileName: String): Boolean {
            val normalized = fileName.replace('\\', '/')
            return normalized.startsWith("__MACOSX/") ||
                    normalized.contains("/__MACOSX/") ||
                    normalized.substringAfterLast('/').startsWith("._") ||
                    normalized.endsWith(".DS_Store", ignoreCase = true) ||
                    normalized.endsWith("Thumbs.db", ignoreCase = true)
        }

        fun isSevenZFile(file: File): Boolean {
            val lower = file.name.lowercase(Locale.ROOT)
            return lower.endsWith(".7z") || lower.endsWith(".cb7") ||
                    SevenZArchiveManager.isValidSevenZArchive(file)
        }
    }

    /**
     * Determines whether the archive has any encrypted entries.
     */
    fun isEncrypted(file: File): Boolean {
        if (isSevenZFile(file)) {
            return sevenZManager.isEncrypted(file)
        }
        return try {
            ZipFile(file).use { zip ->
                if (zip.isEncrypted) return true
                zip.fileHeaders.any { it.isEncrypted }
            }
        } catch (e: Exception) {
            com.watchpicture.app.util.AppLog.e(
                "ZipArchive",
                "判定 zip 加密状态失败: 文件=${file.name.take(48)} 体积=${file.length()}",
                e
            )
            false
        }
    }

    /**
     * Retrieves all browsable media entries (images and videos) sorted in human-intuitive natural order.
     */
    fun getMediaEntries(file: File, password: String? = null): List<ArchiveEntryInfo> {
        if (isSevenZFile(file)) {
            return sevenZManager.getMediaEntries(file, password)
        }

        fun readEntries(charset: Charset?): List<ArchiveEntryInfo> {
            val zip = if (password != null) {
                ZipFile(file, password.toCharArray())
            } else {
                ZipFile(file)
            }
            if (charset != null) {
                zip.charset = charset
            }

            return try {
                zip.use { z ->
                    z.fileHeaders
                        .asSequence()
                        .filter { !it.isDirectory }
                        .filter { !isIgnoredFile(it.fileName) }
                        .filter { isMediaFile(it.fileName) }
                        .map { header ->
                            ArchiveEntryInfo(
                                name = header.fileName,
                                uncompressedSize = header.uncompressedSize,
                                isEncrypted = header.isEncrypted
                            )
                        }
                        .sortedWith { a, b -> naturalOrderComparator.compare(a.name, b.name) }
                        .toList()
                }
            } catch (e: Exception) {
                // release 只保留 ERROR 级日志：条目为空会让用户看到「图包内未发现有效图片」，
                // 必须留痕真实异常（截断副本 / 容器与扩展名不符 / 头部损坏）才能定位。
                com.watchpicture.app.util.AppLog.e(
                    "ZipArchive",
                    "读取 zip 条目失败: 文件=${file.name.take(48)} 体积=${file.length()} " +
                        "字符集=${charset?.name() ?: "默认"}",
                    e
                )
                emptyList()
            }
        }

        fun hasGarbageCharacters(str: String): Boolean {
            // \uFFFD is replacement char; \u2500..\u259F are box/block drawing chars typical of CP437 misinterpreting GBK
            return str.any { it == '\uFFFD' || (it in '\u2500'..'\u259F') || it == '■' }
        }

        fun countChinese(str: String): Int = str.count { it in '\u4e00'..'\u9fa5' }

        val defaultEntries = readEntries(null)
        if (defaultEntries.isEmpty()) {
            return readEntries(GBK_CHARSET)
        }

        val defaultHasGarbage = defaultEntries.any { hasGarbageCharacters(it.name) }
        val defaultChineseCount = defaultEntries.sumOf { countChinese(it.name) }
        val hasNonAscii = defaultEntries.any { it.name.any { ch -> ch.code > 127 } }

        // Fast-path: pure ASCII or clean UTF-8 Chinese entries skip redundant GBK second pass
        if (!defaultHasGarbage && (!hasNonAscii || defaultChineseCount > 0)) {
            return defaultEntries
        }

        // Lazy fallback: only read GBK if default parsing produced garbage or suspicious high-ASCII bytes
        val gbkEntries = readEntries(GBK_CHARSET)
        val gbkHasGarbage = gbkEntries.any { hasGarbageCharacters(it.name) }
        val gbkChineseCount = gbkEntries.sumOf { countChinese(it.name) }

        // If default reading produced CP437 garbage or GBK produced significantly more valid Chinese characters
        if (gbkEntries.isNotEmpty() && (defaultHasGarbage || gbkChineseCount > defaultChineseCount) && !gbkHasGarbage) {
            return gbkEntries
        }

        return defaultEntries
    }

    /**
     * Tests whether the provided password can successfully decrypt an encrypted entry in the archive.
     */
    fun verifyPassword(file: File, password: String): Boolean {
        if (isSevenZFile(file)) {
            return sevenZManager.verifyPassword(file, password)
        }

        fun tryVerify(charset: Charset?): Boolean {
            return try {
                val zip = ZipFile(file, password.toCharArray())
                if (charset != null) {
                    zip.charset = charset
                }
                zip.use { z ->
                    val testHeader = z.fileHeaders
                        .asSequence()
                        .filter { !it.isDirectory }
                        .filter { !isIgnoredFile(it.fileName) }
                        .firstOrNull { it.isEncrypted }
                        ?: z.fileHeaders.firstOrNull { !it.isDirectory && it.isEncrypted }
                        ?: z.fileHeaders.firstOrNull { !it.isDirectory }
                        ?: return true // No file entries to verify

                    val buffer = ByteArray(64)
                    z.getInputStream(testHeader).use { stream ->
                        stream.read(buffer) >= 0
                    }
                    true
                }
            } catch (_: ZipException) {
                false
            } catch (_: Exception) {
                false
            }
        }

        if (tryVerify(null)) return true
        return tryVerify(GBK_CHARSET)
    }

    /**
     * Streams the uncompressed content of a single entry on demand without writing to disk.
     * The returned InputStream is directly backed by the ZipFile entry stream.
     */
    fun getEntryInputStream(file: File, entryName: String, password: String? = null): InputStream {
        if (isSevenZFile(file)) {
            return sevenZManager.getEntryInputStream(file, entryName, password)
        }

        val normalized = entryName.replace('\\', '/')

        // 1. Ultra-fast native path for unencrypted archives (Android C++ zlib backed)
        if (password.isNullOrEmpty()) {
            val nativeStream = handlePool.openNativeEntryStream(file, entryName)
                ?: handlePool.openNativeEntryStream(file, normalized)
                ?: (if (entryName.any { it in '\u4e00'..'\u9fa5' }) handlePool.openNativeEntryStream(file, entryName, GBK_CHARSET) else null)
            if (nativeStream != null) return nativeStream
        } else {
            // 2. Cached handle for encrypted archives
            val encStream = handlePool.openEncryptedEntryStream(file, entryName, password)
                ?: handlePool.openEncryptedEntryStream(file, normalized, password)
                ?: (if (entryName.any { it in '\u4e00'..'\u9fa5' }) handlePool.openEncryptedEntryStream(file, entryName, password, GBK_CHARSET) else null)
            if (encStream != null) return encStream
        }

        val nfcNormalized = java.text.Normalizer.normalize(normalized, java.text.Normalizer.Form.NFC)

        // 3. Fallback for non-standard path variations and legacy charsets
        fun tryOpen(charset: Charset?): InputStream? {
            val zip = if (password != null) {
                ZipFile(file, password.toCharArray())
            } else {
                ZipFile(file)
            }
            if (charset != null) {
                zip.charset = charset
            }

            val header = zip.getFileHeader(entryName)
                ?: zip.getFileHeader(normalized)
                ?: zip.fileHeaders.firstOrNull {
                    val candidate = it.fileName.replace('\\', '/')
                    candidate == normalized ||
                            java.text.Normalizer.normalize(candidate, java.text.Normalizer.Form.NFC) == nfcNormalized
                }

            val stream = header?.let { zip.getInputStream(it) }
                ?: run {
                    // No matching header: the ZipFile must still be closed to avoid leaking a native FD.
                    zip.close()
                    return null
                }

            // Transfer ZipFile ownership to the caller: closing the returned stream also closes
            // the ZipFile, so the FD backing of this fallback stream is always released on close.
            return object : java.io.FilterInputStream(stream) {
                private var closed = false
                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        super.close()
                    } finally {
                        zip.close()
                    }
                }
            }
        }

        val hasChinese = entryName.any { it in '\u4e00'..'\u9fa5' }
        if (hasChinese) {
            val gbkStream = tryOpen(GBK_CHARSET)
            if (gbkStream != null) return gbkStream
        }

        return tryOpen(null)
            ?: tryOpen(GBK_CHARSET)
            ?: throw NoSuchElementException("Entry '$entryName' not found in archive ${file.name}")
    }

    /**
     * Efficiently extracts a batch of entries in sequential order, using single-pass
     * stream extraction for solid 7z archives and pool-cached handles for zip archives.
     */
    fun extractSequentialEntries(
        file: File,
        targetEntryNames: Collection<String>,
        password: String? = null,
        onEntryExtracted: (name: String, stream: InputStream) -> Unit
    ) {
        if (isSevenZFile(file)) {
            sevenZManager.extractSequentialEntries(file, targetEntryNames, password, onEntryExtracted)
            return
        }
        for (name in targetEntryNames) {
            runCatching {
                getEntryInputStream(file, name, password).use { stream ->
                    onEntryExtracted(name, stream)
                }
            }
        }
    }
}
