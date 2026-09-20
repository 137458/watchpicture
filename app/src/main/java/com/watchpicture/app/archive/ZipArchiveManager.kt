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
class ZipArchiveManager {

    companion object {
        private val GBK_CHARSET = Charset.forName("GBK")
        private val SUPPORTED_IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif"
        )
        private val naturalOrderComparator = NaturalOrderComparator()

        fun isImageFile(fileName: String): Boolean {
            val name = fileName.lowercase(Locale.ROOT)
            val ext = name.substringAfterLast('.', "")
            return ext in SUPPORTED_IMAGE_EXTENSIONS
        }

        fun isIgnoredFile(fileName: String): Boolean {
            val normalized = fileName.replace('\\', '/')
            return normalized.startsWith("__MACOSX/") ||
                    normalized.contains("/__MACOSX/") ||
                    normalized.substringAfterLast('/').startsWith("._") ||
                    normalized.endsWith(".DS_Store", ignoreCase = true) ||
                    normalized.endsWith("Thumbs.db", ignoreCase = true)
        }
    }

    /**
     * Determines whether the ZIP archive has any encrypted entries.
     */
    fun isEncrypted(file: File): Boolean {
        return try {
            ZipFile(file).use { zip ->
                if (zip.isEncrypted) return true
                zip.fileHeaders.any { it.isEncrypted }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Retrieves all valid image entries sorted in human-intuitive natural order.
     */
    fun getImageEntries(file: File, password: String? = null): List<ArchiveEntryInfo> {
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
                        .filter { isImageFile(it.fileName) }
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
            } catch (_: Exception) {
                emptyList()
            }
        }

        val entries = readEntries(null)
        if (entries.isNotEmpty()) return entries
        val gbkEntries = readEntries(GBK_CHARSET)
        return if (gbkEntries.isNotEmpty()) gbkEntries else entries
    }

    /**
     * Tests whether the provided password can successfully decrypt an encrypted entry in the archive.
     */
    fun verifyPassword(file: File, password: String): Boolean {
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
        val normalized = entryName.replace('\\', '/')

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
                ?: zip.fileHeaders.firstOrNull { it.fileName.replace('\\', '/') == normalized }

            return header?.let { zip.getInputStream(it) }
        }

        return tryOpen(null)
            ?: tryOpen(GBK_CHARSET)
            ?: throw NoSuchElementException("Entry '$entryName' not found in archive ${file.name}")
    }
}
