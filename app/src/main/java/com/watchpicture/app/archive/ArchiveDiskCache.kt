package com.watchpicture.app.archive

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Dedicated disk cache for streamed decompressed archive entries.
 *
 * Prevents allocating multi-megabyte heap buffers (e.g. okio.Buffer) for large pictures,
 * enables zero-decompression instant re-reading (1ms-5ms), and provides strict LRU eviction
 * and session-scoped encrypted cleanup.
 */
class ArchiveDiskCache(
    private val directory: File,
    private val maxSizeBytes: Long = DEFAULT_MAX_CACHE_SIZE
) {

    companion object {
        const val DEFAULT_MAX_CACHE_SIZE = 512L * 1024 * 1024 // 512 MB
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB streaming buffer
    }

    private val keyLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val globalLock = ReentrantLock()

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    /**
     * Retrieves an already cached file or streams it directly from the archive via [openStream].
     * Writing uses atomic rename (.tmp -> target) to ensure thread-safety and avoid partial files.
     */
    fun getOrPut(
        zipFile: File,
        entryName: String,
        password: String?,
        openStream: () -> InputStream
    ): File {
        val isEncrypted = !password.isNullOrEmpty()
        val cacheKey = computeKey(zipFile, entryName, password)
        val filePrefix = if (isEncrypted) "enc_" else "raw_"
        val ext = entryName.substringAfterLast('.', "dat").lowercase()
        val targetFile = File(directory, "$filePrefix$cacheKey.$ext")

        if (targetFile.exists() && targetFile.length() > 0) {
            targetFile.setLastModified(System.currentTimeMillis())
            return targetFile
        }

        val keyLock = keyLocks.computeIfAbsent(cacheKey) { ReentrantLock() }
        keyLock.withLock {
            // Re-check under key lock
            if (targetFile.exists() && targetFile.length() > 0) {
                targetFile.setLastModified(System.currentTimeMillis())
                return targetFile
            }

            val tempFile = File(directory, "${targetFile.name}.${System.nanoTime()}.tmp")
            try {
                openStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                        output.flush()
                    }
                }

                if (tempFile.exists() && tempFile.length() > 0) {
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    val renamed = tempFile.renameTo(targetFile)
                    if (!renamed) {
                        // Fallback copy if atomic rename fails on some file systems
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                }
            } catch (e: Throwable) {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                throw e
            } finally {
                trimToSize()
            }

            return targetFile
        }
    }

    /**
     * Evicts oldest accessed files until total size is within [maxSizeBytes].
     */
    fun trimToSize() {
        globalLock.withLock {
            val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= maxSizeBytes) return

            // Sort by last modified ascending (LRU)
            val sorted = files.sortedBy { it.lastModified() }
            for (file in sorted) {
                val size = file.length()
                if (file.delete()) {
                    totalSize -= size
                    if (totalSize <= maxSizeBytes) break
                }
            }
        }
    }

    /**
     * Immediately deletes all cached entries originating from encrypted archives.
     */
    fun clearEncrypted() {
        globalLock.withLock {
            val files = directory.listFiles()?.filter { it.isFile && it.name.startsWith("enc_") } ?: return
            for (file in files) {
                file.delete()
            }
        }
    }

    /**
     * Completely purges the archive cache.
     */
    fun clearAll() {
        globalLock.withLock {
            directory.listFiles()?.forEach { it.delete() }
        }
    }

    private fun computeKey(zipFile: File, entryName: String, password: String?): String {
        val raw = "${zipFile.absolutePath}#${zipFile.lastModified()}#$entryName#${password ?: "none"}"
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
