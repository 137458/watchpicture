package com.watchpicture.app.archive

import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.io.inputstream.ZipInputStream as Zip4jInputStream
import net.lingala.zip4j.io.inputstream.ZipStandardSplitFileInputStream
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.Zip4jConfig
import net.lingala.zip4j.model.enums.EncryptionMethod
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

        /**
         * Zip4j 读取管线默认 4KB 缓冲（调研 Z2 项）：每条目读取的 fill() 粒度过细，
         * 扩容到 256KB 后大图条目吞吐显著提升。仅影响 Zip4j 流（ZipCrypto / 回退路径），
         * AES 条目由 [ZipAesJceStreamFactory] 自带同规格缓冲。
         */
        const val ZIP4J_STREAM_BUFFER_SIZE = 256 * 1024
    }

    private val aesStreamFactory = ZipAesJceStreamFactory()

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
     * Obtains an InputStream for an entry in an encrypted ZIP, or directly from a local
     * directory if file is a directory.
     *
     * The pooled Zip4j instance is only used for central-directory header lookup and is
     * released immediately afterwards; the returned stream owns its dedicated
     * RandomAccessFile, so concurrent stream count is no longer capped by the instance pool.
     * WinZip AES entries take the JCE hardware-accelerated pipeline ([ZipAesJceStreamFactory]);
     * ZipCrypto entries stay on Zip4j with an enlarged read buffer.
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

        val header = try {
            val normalized = entryName.replace('\\', '/')
            zip4jInstance.getFileHeader(entryName)
                ?: zip4jInstance.getFileHeader(normalized)
                ?: zip4jInstance.fileHeaders.firstOrNull { it.fileName.replace('\\', '/') == normalized }
        } catch (_: Exception) {
            handle.release(zip4jInstance)
            return null
        }

        if (header == null) {
            handle.release(zip4jInstance)
            return null
        }
        handle.release(zip4jInstance)

        return if (header.isEncrypted && header.encryptionMethod == EncryptionMethod.AES) {
            // JCE 管线：错误密码在 verifier 校验时即抛 ZipException(WRONG_PASSWORD)。
            aesStreamFactory.open(file, header, password.toCharArray())
        } else {
            openZip4jCryptoEntryStream(file, header, password, charset)
        }
    }

    /**
     * Builds a Zip4j stream for a (ZipCrypto) entry with an enlarged read buffer,
     * bypassing [Zip4jFile.getInputStream] whose buffer size is fixed at 4KB.
     */
    private fun openZip4jCryptoEntryStream(
        file: File,
        header: FileHeader,
        password: String,
        charset: Charset?
    ): InputStream? {
        val splitStream = try {
            // 非分卷归档：numberOfThisDisk 恒为 0。
            ZipStandardSplitFileInputStream(file, false, 0)
        } catch (_: Exception) {
            return null
        }
        return try {
            splitStream.prepareExtractionForFileHeader(header)
            val config = Zip4jConfig(charset, ZIP4J_STREAM_BUFFER_SIZE, true)
            val stream = Zip4jInputStream(splitStream, password.toCharArray(), config)
            if (stream.getNextEntry(header, false) == null) {
                stream.close()
                null
            } else {
                stream
            }
        } catch (_: Exception) {
            runCatching { splitStream.close() }
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
