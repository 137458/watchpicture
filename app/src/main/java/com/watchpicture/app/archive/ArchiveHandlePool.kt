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
        const val DEFAULT_MAX_INSTANCES_PER_ARCHIVE = 4
    }

    private val lock = ReentrantLock()

    private data class NativeHandle(
        val zipFile: NativeZipFile,
        val lastModified: Long
    )

    // The full password (not just an int hash) is part of the key: int hashes collide for
    // different passwords, which would silently reuse a handle opened with the wrong password
    // and decrypt/corrupt data.
    private data class EncryptedHandleKey(
        val path: String,
        val password: String
    )

    private class EncryptedHandle(
        val file: File,
        val password: String,
        val lastModified: Long,
        private val maxInstances: Int = DEFAULT_MAX_INSTANCES_PER_ARCHIVE
    ) {
        private val handleLock = ReentrantLock()
        private val idleInstances = ArrayDeque<Zip4jFile>()
        private val allInstances = mutableSetOf<Zip4jFile>()
        private var isClosed = false

        fun borrow(charset: Charset?): Zip4jFile? = handleLock.withLock {
            if (isClosed) return null
            val instance = idleInstances.removeFirstOrNull() ?: run {
                try {
                    val newZip = Zip4jFile(file, password.toCharArray()).apply {
                        if (charset != null) this.charset = charset
                    }
                    allInstances.add(newZip)
                    newZip
                } catch (_: Exception) {
                    return null
                }
            }
            if (charset != null && instance.charset != charset) {
                instance.charset = charset
            }
            instance
        }

        fun release(instance: Zip4jFile) = handleLock.withLock {
            if (isClosed || idleInstances.size >= maxInstances) {
                allInstances.remove(instance)
                runCatching { instance.close() }
            } else {
                idleInstances.addLast(instance)
            }
        }

        fun close() = handleLock.withLock {
            isClosed = true
            for (instance in allInstances) {
                runCatching { instance.close() }
            }
            idleInstances.clear()
            allInstances.clear()
        }
    }

    private class PooledZip4jInputStream(
        private val delegate: InputStream,
        private val lockTarget: Any,
        private val onClose: () -> Unit
    ) : InputStream() {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun read(): Int = synchronized(lockTarget) {
            if (closed.get()) throw java.io.IOException("Stream closed")
            delegate.read()
        }

        override fun read(b: ByteArray): Int = synchronized(lockTarget) {
            if (closed.get()) throw java.io.IOException("Stream closed")
            delegate.read(b, 0, b.size)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(lockTarget) {
            if (closed.get()) throw java.io.IOException("Stream closed")
            delegate.read(b, off, len)
        }

        override fun skip(n: Long): Long = synchronized(lockTarget) {
            if (closed.get()) throw java.io.IOException("Stream closed")
            delegate.skip(n)
        }

        override fun available(): Int = synchronized(lockTarget) {
            if (closed.get()) 0 else delegate.available()
        }

        override fun mark(readlimit: Int) = synchronized(lockTarget) {
            delegate.mark(readlimit)
        }

        override fun reset() = synchronized(lockTarget) {
            delegate.reset()
        }

        override fun markSupported(): Boolean = synchronized(lockTarget) {
            delegate.markSupported()
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    synchronized(lockTarget) {
                        delegate.close()
                    }
                } finally {
                    onClose()
                }
            }
        }
    }

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
                runCatching { eldest.value.close() }
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
     * Obtains an InputStream for an entry using native C++ zlib-backed java.util.zip.ZipFile,
     * or directly from a local directory if file is a directory.
     * Returns null if entry is not found or file cannot be opened.
     */
    fun openNativeEntryStream(
        file: File,
        entryName: String,
        charset: Charset? = null
    ): InputStream? {
        if (file.isDirectory) {
            val cleanName = entryName.trimStart('/', '\\')
            val target = File(file, cleanName)
            val normalized = cleanName.replace('\\', '/')
            val resolved = if (target.exists() && target.isFile) {
                target
            } else {
                val alt = File(file, normalized)
                if (alt.exists() && alt.isFile) alt else null
            }
            return if (resolved != null) {
                try {
                    java.io.FileInputStream(resolved)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }

        val path = file.absolutePath
        val lastModified = file.lastModified()

        // Handle lookup AND entry/stream acquisition must happen inside the same
        // locked section: a concurrent put() may trigger removeEldestEntry() which
        // closes the eldest handle. If the handle were used outside the lock it
        // could be closed in between, throwing on a closed handle.
        return lock.withLock {
            val existing = nativePool[path]
            val handle = if (existing != null && existing.lastModified == lastModified) {
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
                    return@withLock null
                }
                val created = NativeHandle(newZip, lastModified)
                nativePool[path] = created
                created
            }

            val entry = handle.zipFile.getEntry(entryName)
                ?: handle.zipFile.getEntry(entryName.replace('\\', '/'))
                ?: return@withLock null

            try {
                handle.zipFile.getInputStream(entry)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Obtains an InputStream for an entry in an encrypted ZIP using Zip4j,
     * or directly from a local directory if file is a directory.
     * Each concurrent stream borrows an isolated Zip4jFile instance from the handle's object pool
     * and synchronizes read access to eliminate underlying RandomAccessFile pointer collision.
     */
    fun openEncryptedEntryStream(
        file: File,
        entryName: String,
        password: String,
        charset: Charset? = null
    ): InputStream? {
        if (file.isDirectory) {
            return openNativeEntryStream(file, entryName, charset)
        }

        val path = file.absolutePath
        val lastModified = file.lastModified()
        val key = EncryptedHandleKey(path, password)

        val handle = lock.withLock {
            val existing = encryptedPool[key]
            if (existing != null && existing.lastModified == lastModified) {
                existing
            } else {
                existing?.close()
                val created = EncryptedHandle(file, password, lastModified)
                encryptedPool[key] = created
                created
            }
        }

        val zip4jInstance = handle.borrow(charset) ?: return null

        val stream = try {
            val normalized = entryName.replace('\\', '/')
            val header = zip4jInstance.getFileHeader(entryName)
                ?: zip4jInstance.getFileHeader(normalized)
                ?: zip4jInstance.fileHeaders.firstOrNull { it.fileName.replace('\\', '/') == normalized }

            if (header == null) {
                handle.release(zip4jInstance)
                return null
            }
            zip4jInstance.getInputStream(header)
        } catch (_: Exception) {
            handle.release(zip4jInstance)
            return null
        }

        if (stream == null) {
            handle.release(zip4jInstance)
            return null
        }

        return PooledZip4jInputStream(stream, zip4jInstance) {
            handle.release(zip4jInstance)
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
                    runCatching { it.close() }
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
                runCatching { h.close() }
            }
            encryptedPool.clear()
        }
    }
}
