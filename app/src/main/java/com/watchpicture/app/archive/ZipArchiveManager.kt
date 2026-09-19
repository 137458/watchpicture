package com.watchpicture.app.archive

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * High-performance, streaming archive manager powered by Zip4j.
 * Strictly avoids extracting archives to disk; delivers entries as memory streams.
 */
class ZipArchiveManager {

    companion object {
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
        val zip = if (password != null) {
            ZipFile(file, password.toCharArray())
        } else {
            ZipFile(file)
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

    /**
     * Tests whether the provided password can successfully decrypt an encrypted entry in the archive.
     */
    fun verifyPassword(file: File, password: String): Boolean {
        return try {
            ZipFile(file, password.toCharArray()).use { zip ->
                val encryptedHeader = zip.fileHeaders.firstOrNull { it.isEncrypted }
                    ?: return true // No encrypted entries found

                val buffer = ByteArray(64)
                zip.getInputStream(encryptedHeader).use { stream ->
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

    /**
     * Streams the uncompressed content of a single entry on demand without writing to disk.
     * The returned InputStream is directly backed by the ZipFile entry stream.
     */
    fun getEntryInputStream(file: File, entryName: String, password: String? = null): InputStream {
        val zip = if (password != null) {
            ZipFile(file, password.toCharArray())
        } else {
            ZipFile(file)
        }

        val header = zip.getFileHeader(entryName)
            ?: throw NoSuchElementException("Entry '$entryName' not found in archive ${file.name}")

        return zip.getInputStream(header)
    }
}
