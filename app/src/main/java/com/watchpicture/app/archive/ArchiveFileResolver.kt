package com.watchpicture.app.archive

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.watchpicture.app.model.PackImage
import com.watchpicture.app.model.ZipPack
import com.watchpicture.app.security.SessionPasswordStore
import com.watchpicture.app.storage.SafManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Resolves single archive files (ZIP / CBZ) from either direct filesystem paths
 * or SAF / ContentProvider Uris with optimized zero-copy and fallback streaming cache.
 */
class ArchiveFileResolver(
    private val zipArchiveManager: ZipArchiveManager,
    private val passwordStore: SessionPasswordStore,
    private val safManager: SafManager = SafManager(zipArchiveManager, passwordStore)
) {

    companion object {
        private const val BUFFER_SIZE = 64 * 1024 // 64KB high-speed stream buffer
    }

    /**
     * Quickly verifies if a file starts with valid ZIP magic bytes:
     * - PK\x03\x04 (0x50 0x4B 0x03 0x04) standard entry
     * - PK\x05\x06 (0x50 0x4B 0x05 0x06) empty archive
     * - PK\x07\x08 (0x50 0x4B 0x07 0x08) spanned archive
     */
    fun isValidZipArchive(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            FileInputStream(file).use { stream ->
                val magic = ByteArray(4)
                if (stream.read(magic) != 4) return false
                magic[0] == 0x50.toByte() &&
                        magic[1] == 0x4B.toByte() &&
                        ((magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()) ||
                                (magic[2] == 0x05.toByte() && magic[3] == 0x06.toByte()) ||
                                (magic[2] == 0x07.toByte() && magic[3] == 0x08.toByte()))
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Constructs a ZipPack model from a resolved local file.
     */
    fun createZipPackFromFile(
        file: File,
        uriString: String? = null,
        cachedPassword: String? = null
    ): ZipPack? {
        if (!isValidZipArchive(file)) return null

        val lowerName = file.name.lowercase()
        val isCbz = lowerName.endsWith(".cbz")
        val isEncrypted = zipArchiveManager.isEncrypted(file)
        val password = cachedPassword ?: passwordStore.get(file.absolutePath)
        val entries = zipArchiveManager.getImageEntries(file, password)

        val fallbackUri = uriString ?: "file://${file.absolutePath}"
        val cover = entries.firstOrNull()?.let { entry ->
            PackImage(
                packId = file.absolutePath,
                entryPath = entry.name,
                displayName = entry.name.substringAfterLast('/'),
                isEncrypted = entry.isEncrypted,
                directFilePath = file.absolutePath,
                fileUri = fallbackUri
            )
        }

        val name = file.nameWithoutExtension.ifEmpty { file.name }
        return ZipPack(
            id = file.absolutePath,
            name = name,
            uriString = fallbackUri,
            directPath = file.absolutePath,
            itemCount = entries.size,
            isEncrypted = isEncrypted,
            fileSize = file.length(),
            lastModified = file.lastModified(),
            coverImage = cover,
            isCbz = isCbz
        )
    }

    /**
     * Resolves an arbitrary Uri (direct file, SAF document, or external ContentProvider)
     * to a local File and returns the corresponding ZipPack.
     */
    suspend fun resolve(context: Context, uri: Uri): ZipPack? = withContext(Dispatchers.IO) {
        // Step 1: Zero-copy direct resolution
        val directFile = safManager.resolveDirectFile(uri)
        if (directFile != null && directFile.exists() && directFile.canRead()) {
            val pack = createZipPackFromFile(directFile, uri.toString())
            if (pack != null) return@withContext pack
        }

        // Step 2: Fallback streaming cache for restricted ContentProviders
        val cachedFile = cacheFromContentProvider(context, uri) ?: return@withContext null
        createZipPackFromFile(cachedFile, uri.toString())
    }

    /**
     * Streams content from ContentResolver into an application cache file using a 64KB buffer.
     * Reuses existing cache if the file size and content match.
     */
    private fun cacheFromContentProvider(context: Context, uri: Uri): File? {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri) ?: "archive_${System.currentTimeMillis()}.zip"
        val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val cacheDir = File(context.cacheDir, "opened_archives").apply { mkdirs() }

        // Generate stable cache file name based on Uri hash and filename
        val uriHash = (uri.toString().hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        val targetFile = File(cacheDir, "${uriHash}_$safeName")

        val expectedSize = queryFileSize(context, uri)
        if (targetFile.exists() && targetFile.length() > 0 && isValidZipArchive(targetFile)) {
            if (expectedSize == null || expectedSize == targetFile.length()) {
                return targetFile // Cache hit, skip redundant I/O
            }
        }

        val tempFile = File(cacheDir, "${targetFile.name}.tmp")
        return try {
            val inputStream: InputStream = resolver.openInputStream(uri) ?: return null
            BufferedInputStream(inputStream, BUFFER_SIZE).use { input ->
                BufferedOutputStream(FileOutputStream(tempFile), BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }

            if (tempFile.exists() && tempFile.length() > 0 && isValidZipArchive(tempFile)) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                targetFile
            } else {
                tempFile.delete()
                null
            }
        } catch (_: Exception) {
            if (tempFile.exists()) tempFile.delete()
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment
        }
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            } ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    private fun queryFileSize(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
