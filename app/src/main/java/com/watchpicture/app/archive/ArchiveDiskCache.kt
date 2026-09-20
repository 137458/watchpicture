package com.watchpicture.app.archive

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Dedicated disk cache for streamed decompressed archive entries.
 *
 * Prevents allocating multi-megabyte heap buffers (e.g. okio.Buffer) for large pictures,
 * enables zero-decompression instant re-reading (1ms-5ms), and provides strict LRU eviction
 * and session-scoped encrypted cleanup.
 *
 * Employs 64-way striped locks and an atomic size counter to completely eliminate
 * directory list/stat contention during high-concurrency thumbnail loading.
 */
class ArchiveDiskCache(
    private val directory: File,
    private val maxSizeBytes: Long = DEFAULT_MAX_CACHE_SIZE
) {

    companion object {
        const val DEFAULT_MAX_CACHE_SIZE = 512L * 1024 * 1024 // 512 MB
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB streaming buffer
        private const val STRIPE_COUNT = 64
    }

    private val stripeLocks = Array(STRIPE_COUNT) { ReentrantLock() }
    private val globalLock = ReentrantLock()
    private val currentSizeBytes = AtomicLong(-1L)

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    private fun getLockFor(key: String): ReentrantLock {
        val hash = key.hashCode()
        val index = (hash and 0x7FFFFFFF) % STRIPE_COUNT
        return stripeLocks[index]
    }

    private fun getOrInitSize(): Long {
        val current = currentSizeBytes.get()
        if (current >= 0L) return current

        globalLock.withLock {
            val recheck = currentSizeBytes.get()
            if (recheck >= 0L) return recheck

            val total = directory.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".tmp") }
                ?.sumOf { it.length() } ?: 0L
            currentSizeBytes.set(total)
            return total
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

        // Fast path without lock
        if (targetFile.exists() && targetFile.length() > 0) {
            targetFile.setLastModified(System.currentTimeMillis())
            return targetFile
        }

        val stripeLock = getLockFor(cacheKey)
        stripeLock.withLock {
            // Re-check under striped lock
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

                if (!tempFile.exists() || tempFile.length() <= 0L) {
                    if (tempFile.exists()) tempFile.delete()
                    throw IOException("Failed to extract '$entryName': stream produced 0 bytes")
                }

                if (targetFile.exists()) {
                    targetFile.delete()
                }
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    // Fallback copy if atomic rename fails on some file systems
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }

                val addedSize = targetFile.length()
                getOrInitSize()
                val updatedSize = currentSizeBytes.addAndGet(addedSize)
                if (updatedSize > maxSizeBytes) {
                    trimToSize()
                }
            } catch (e: Throwable) {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                throw e
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
            currentSizeBytes.set(totalSize)
            if (totalSize <= maxSizeBytes) return

            // Sort by last modified ascending (LRU)
            val sorted = files.sortedBy { it.lastModified() }
            for (file in sorted) {
                val size = file.length()
                if (file.delete()) {
                    totalSize -= size
                    currentSizeBytes.addAndGet(-size)
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
            var deletedBytes = 0L
            for (file in files) {
                val len = file.length()
                if (file.delete()) {
                    deletedBytes += len
                }
            }
            if (currentSizeBytes.get() >= 0L) {
                currentSizeBytes.addAndGet(-deletedBytes).coerceAtLeast(0L).also {
                    if (it < 0L) currentSizeBytes.set(0L)
                }
            }
        }
    }

    /**
     * Completely purges the archive cache.
     */
    fun clearAll() {
        globalLock.withLock {
            directory.listFiles()?.forEach { it.delete() }
            currentSizeBytes.set(0L)
        }
    }

    private fun computeKey(zipFile: File, entryName: String, password: String?): String {
        val raw = "${zipFile.absolutePath}#${zipFile.lastModified()}#$entryName#${password ?: "none"}"
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
