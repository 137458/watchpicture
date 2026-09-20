package com.watchpicture.app.archive

import net.lingala.zip4j.ZipFile as Zip4jFile
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipFile as NativeZipFile
import kotlin.concurrent.withLock

/**
 * Thread-safe LRU archive handle pool.
 *
 * Keeps active archive handles open to completely eliminate the costly repetitive
 * central directory parsing (EOCD seek and FileHeader scanning) during high-frequency
 * thumbnail loading and gallery page swiping.
 */
class ArchiveHandlePool(
    private val maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE
) {
    companion object {
        const val DEFAULT_MAX_POOL_SIZE = 4
    }

    private val lock = ReentrantLock()

    private data class NativeHandle(
        val zipFile: NativeZipFile,
        val lastModified: Long
    )

    private data class EncryptedHandleKey(
        val path: String,
        val passwordHash: Int
    )

    private data class EncryptedHandle(
        val zip4jFile: Zip4jFile,
        val lastModified: Long
    )

    private val nativePool = object : LinkedHashMap<String, NativeHandle>(maxPoolSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NativeHandle>?): Boolean {
            if (size > maxPoolSize && eldest != null) {
                runCatching { eldest.value.zipFile.close() }
                return true
            }
            return false
        }
    }

    private val encryptedPool = object : LinkedHashMap<EncryptedHandleKey, EncryptedHandle>(maxPoolSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EncryptedHandleKey, EncryptedHandle>?): Boolean {
            if (size > maxPoolSize && eldest != null) {
                runCatching { eldest.value.zip4jFile.close() }
                return true
            }
            return false
        }
    }

    val activeNativeHandleCount: Int
        get() = lock.withLock { nativePool.size }

    val activeEncryptedHandleCount: Int
        get() = lock.withLock { encryptedPool.size }

    /**
     * Obtains an InputStream for an entry using native C++ zlib-backed java.util.zip.ZipFile.
     * Returns null if entry is not found or file cannot be opened.
     */
    fun openNativeEntryStream(
        file: File,
        entryName: String,
        charset: Charset? = null
    ): InputStream? {
        val path = file.absolutePath
        val lastModified = file.lastModified()

        val handle = lock.withLock {
            val existing = nativePool[path]
            if (existing != null && existing.lastModified == lastModified) {
                existing
            } else {
                existing?.let { runCatching { it.zipFile.close() } }
                val newZip = try {
                    if (charset != null) {
                        NativeZipFile(file, charset)
                    } else {
                        NativeZipFile(file)
                    }
                } catch (_: Exception) {
                    return null
                }
                val created = NativeHandle(newZip, lastModified)
                nativePool[path] = created
                created
            }
        }

        val entry = handle.zipFile.getEntry(entryName)
            ?: handle.zipFile.getEntry(entryName.replace('\\', '/'))
            ?: return null

        return try {
            handle.zipFile.getInputStream(entry)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Obtains an InputStream for an entry in an encrypted ZIP using Zip4j.
     */
    fun openEncryptedEntryStream(
        file: File,
        entryName: String,
        password: String,
        charset: Charset? = null
    ): InputStream? {
        val path = file.absolutePath
        val lastModified = file.lastModified()
        val key = EncryptedHandleKey(path, password.hashCode())

        val handle = lock.withLock {
            val existing = encryptedPool[key]
            if (existing != null && existing.lastModified == lastModified) {
                existing
            } else {
                existing?.let { runCatching { it.zip4jFile.close() } }
                val newZip = try {
                    Zip4jFile(file, password.toCharArray()).apply {
                        if (charset != null) this.charset = charset
                    }
                } catch (_: Exception) {
                    return null
                }
                val created = EncryptedHandle(newZip, lastModified)
                encryptedPool[key] = created
                created
            }
        }

        val normalized = entryName.replace('\\', '/')
        val header = handle.zip4jFile.getFileHeader(entryName)
            ?: handle.zip4jFile.getFileHeader(normalized)
            ?: handle.zip4jFile.fileHeaders.firstOrNull { it.fileName.replace('\\', '/') == normalized }
            ?: return null

        return try {
            handle.zip4jFile.getInputStream(header)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Explicitly closes and removes cached handles for a file.
     */
    fun close(file: File) {
        val path = file.absolutePath
        lock.withLock {
            nativePool.remove(path)?.let {
                runCatching { it.zipFile.close() }
            }
            val keysToRemove = encryptedPool.keys.filter { it.path == path }
            for (k in keysToRemove) {
                encryptedPool.remove(k)?.let {
                    runCatching { it.zip4jFile.close() }
                }
            }
        }
    }

    /**
     * Closes all active archive handles in the pool.
     */
    fun closeAll() {
        lock.withLock {
            for (h in nativePool.values) {
                runCatching { h.zipFile.close() }
            }
            nativePool.clear()
            for (h in encryptedPool.values) {
                runCatching { h.zip4jFile.close() }
            }
            encryptedPool.clear()
        }
    }
}
