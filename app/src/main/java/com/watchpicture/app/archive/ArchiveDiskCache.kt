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
    val directory: File,
    private val maxSizeBytes: Long = DEFAULT_MAX_CACHE_SIZE
) {

    companion object {
        const val DEFAULT_MAX_CACHE_SIZE = 512L * 1024 * 1024 // 512 MB
        private const val BUFFER_SIZE = 128 * 1024 // 128 KB streaming buffer for high-throughput big pictures
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
     * Checks whether an entry is already cached and returns the cached file if present, or null.
     */
    fun get(
        zipFile: File,
        entryName: String,
        password: String?
    ): File? {
        val isEncrypted = !password.isNullOrEmpty()
        val cacheKey = computeKey(zipFile, entryName, password)
        val filePrefix = if (isEncrypted) "enc_" else "raw_"
        val ext = entryName.substringAfterLast('.', "dat").lowercase()
        val targetFile = File(directory, "$filePrefix$cacheKey.$ext")
        if (targetFile.exists() && targetFile.length() > 0) {
            // mtime update must happen inside the stripe lock to stay ordered with trimToSize(),
            // otherwise a just-hit file could be evicted as LRU in the same instant.
            getLockFor(cacheKey).withLock {
                targetFile.setLastModified(System.currentTimeMillis())
            }
            return targetFile
        }
        return null
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
    ): File = putDirect(zipFile, entryName, password) { tempFile ->
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
    }

    /**
     * Retrieves an already cached file or writes directly to [tempTargetFile] via [writer]
     * without intermediate file copying. Atomically renames to target file upon successful completion.
     */
    fun putDirect(
        zipFile: File,
        entryName: String,
        password: String?,
        writer: (tempTargetFile: File) -> Unit
    ): File {
        val isEncrypted = !password.isNullOrEmpty()
        val cacheKey = computeKey(zipFile, entryName, password)
        val filePrefix = if (isEncrypted) "enc_" else "raw_"
        val ext = entryName.substringAfterLast('.', "dat").lowercase()
        val targetFile = File(directory, "$filePrefix$cacheKey.$ext")

        // Fast path without lock
        if (targetFile.exists() && targetFile.length() > 0) {
            getLockFor(cacheKey).withLock {
                targetFile.setLastModified(System.currentTimeMillis())
            }
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
                writer(tempFile)

                if (!tempFile.exists() || tempFile.length() <= 0L) {
                    if (tempFile.exists()) tempFile.delete()
                    throw IOException("Failed to extract '$entryName': writer produced 0 bytes")
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
     * Retrieves an already cached file or writes directly to [tempTargetFile] via suspending [writer]
     * without intermediate file copying. Atomically renames to target file upon successful completion.
     */
    suspend fun putDirectSuspend(
        zipFile: File,
        entryName: String,
        password: String?,
        writer: suspend (tempTargetFile: File) -> Unit
    ): File {
        val isEncrypted = !password.isNullOrEmpty()
        val cacheKey = computeKey(zipFile, entryName, password)
        val filePrefix = if (isEncrypted) "enc_" else "raw_"
        val ext = entryName.substringAfterLast('.', "dat").lowercase()
        val targetFile = File(directory, "$filePrefix$cacheKey.$ext")

        val stripeLock = getLockFor(cacheKey)

        // Fast path without lock (mtime update below happens under the lock to stay LRU-safe).
        if (targetFile.exists() && targetFile.length() > 0) {
            stripeLock.withLock {
                targetFile.setLastModified(System.currentTimeMillis())
            }
            return targetFile
        }

        // Decompress *outside* the lock: [writer] is suspending and may migrate threads on a
        // bounded pool, so holding a thread-pinned ReentrantLock across it could deadlock the pool
        // or crash on unlock() from a different thread.
        val tempFile = File(directory, "${targetFile.name}.${System.nanoTime()}.tmp")
        try {
            writer(tempFile)

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                if (tempFile.exists()) tempFile.delete()
                throw IOException("Failed to extract '$entryName': writer produced 0 bytes")
            }

            stripeLock.withLock {
                // Dedupe concurrent writers for this key: if a peer already renamed the target file
                // while we were decompressing, reuse it and drop our temp WITHOUT double-counting the
                // size (previously both writers addAndGet()'d, inflating the size and evicting real LRU).
                if (targetFile.exists() && targetFile.length() > 0) {
                    tempFile.delete()
                    targetFile.setLastModified(System.currentTimeMillis())
                    return targetFile
                }
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }

                val addedSize = targetFile.length()
                getOrInitSize()
                val updatedSize = currentSizeBytes.addAndGet(addedSize)
                if (updatedSize > maxSizeBytes) {
                    trimToSize()
                }
                return targetFile
            }
        } catch (e: Throwable) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw e
        }
    }

    /**
     * 统计缓存目录当前占用的字节数（忽略写入中的 .tmp 半成品）。
     */
    fun sizeOnDisk(): Long = cacheDirectorySize(directory)

    /**
     * Evicts oldest accessed files until total size is within [maxSizeBytes].
     */
    fun trimToSize() {
        globalLock.withLock {
            val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
            val entries = files.map { LruCacheEntry(it.absolutePath, it.length(), it.lastModified()) }
            currentSizeBytes.set(entries.sumOf { it.sizeBytes })

            // 在途读取持有的租约文件不得淘汰，其占用仍计入总量
            val victims = planLruTrim(entries, maxSizeBytes) { !CacheFileLeases.isLeased(it.path) }
            var freed = 0L
            for (path in victims) {
                val file = File(path)
                val size = file.length()
                if (file.delete()) freed += size
            }
            if (freed > 0L) currentSizeBytes.addAndGet(-freed)
        }
    }

    /**
     * Immediately deletes all cached entries originating from encrypted archives.
     */
    fun clearEncrypted() {
        globalLock.withLock {
            val files = directory.listFiles()?.filter {
                it.isFile && it.name.startsWith("enc_") && !CacheFileLeases.isLeased(it.absolutePath)
            } ?: return
            var deletedBytes = 0L
            for (file in files) {
                val len = file.length()
                if (file.delete()) {
                    deletedBytes += len
                }
            }
            if (deletedBytes > 0L && currentSizeBytes.get() >= 0L) {
                currentSizeBytes.updateAndGet { (it - deletedBytes).coerceAtLeast(0L) }
            }
        }
    }

    /**
     * Completely purges the archive cache.
     */
    fun clearAll() {
        globalLock.withLock {
            // Skip files still leased by an in-flight reader; they are cleaned up later.
            directory.listFiles()?.forEach {
                if (!CacheFileLeases.isLeased(it.absolutePath)) it.delete()
            }
            // Leased files may remain, so recompute the tracked size from disk.
            currentSizeBytes.set(
                directory.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".tmp") }
                    ?.sumOf { it.length() } ?: 0L
            )
        }
    }

    private fun computeKey(zipFile: File, entryName: String, password: String?): String {
        // Embed only a one-way SHA-256 digest of the password, never the plaintext, so the key
        // material in a heap dump does not leak the credential. Different passwords still map to
        // distinct keys (preserving per-password cache isolation).
        val pwdToken = if (password.isNullOrEmpty()) {
            "none"
        } else {
            MessageDigest.getInstance("SHA-256")
                .digest(password.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        val raw = "${zipFile.absolutePath}#${zipFile.lastModified()}#$entryName#$pwdToken"
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 统计缓存目录占用的字节数（忽略写入中的 .tmp 半成品）。
 */
internal fun cacheDirectorySize(directory: File): Long = directory.listFiles()
    ?.filter { it.isFile && !it.name.endsWith(".tmp") }
    ?.sumOf { it.length() }
    ?: 0L

/**
 * 缓存文件 LRU 淘汰所需的最小信息。
 */
internal data class LruCacheEntry(
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long
)

/**
 * 计算把缓存裁剪到 [maxBytes] 预算以内需要删除的文件路径，按最久未使用（lastModified 升序）优先。
 * 当前占用未超预算时返回空列表。
 *
 * @param evictable 判定某条目当前是否允许删除；被占用的条目（例如仍有在途读取的租约文件）返回 false，
 *   这类条目会被跳过，其占用仍计入总量。
 */
internal fun planLruTrim(
    entries: List<LruCacheEntry>,
    maxBytes: Long,
    evictable: (LruCacheEntry) -> Boolean = { true }
): List<String> {
    val usable = entries.filter { it.sizeBytes > 0L }
    var remaining = usable.sumOf { it.sizeBytes }
    if (remaining <= maxBytes) return emptyList()

    val victims = mutableListOf<String>()
    for (entry in usable.sortedBy { it.lastModified }) {
        if (!evictable(entry)) continue
        victims.add(entry.path)
        remaining -= entry.sizeBytes
        if (remaining <= maxBytes) break
    }
    return victims
}
