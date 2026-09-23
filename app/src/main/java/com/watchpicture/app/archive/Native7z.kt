package com.watchpicture.app.archive

import androidx.annotation.Keep
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Metadata for an entry inside a 7z archive parsed by the native 7-Zip C SDK.
 */
@Keep
data class Native7zEntry(
    val index: Int,
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
    val crc: Long
)

/**
 * JNI bindings to the official 7-Zip ANSI-C LZMA SDK engine.
 */
@Keep
object Native7z {
    @Volatile
    private var libraryLoaded: Boolean? = null

    /**
     * Indicates whether the native library is successfully loaded and available in this runtime.
     * Evaluates to false gracefully on host JVM environments (e.g. desktop unit tests) without throwing.
     */
    val isAvailable: Boolean
        get() {
            libraryLoaded?.let { return it }
            return synchronized(this) {
                libraryLoaded?.let { return it }
                val loaded = try {
                    System.loadLibrary("native7z")
                    true
                } catch (t: Throwable) {
                    false
                }
                libraryLoaded = loaded
                loaded
            }
        }

    @JvmStatic
    @Throws(IOException::class)
    external fun nativeOpen(path: String, password: String? = null): Long

    @JvmStatic
    @Throws(IOException::class)
    external fun nativeOpenFd(fd: Int, password: String? = null): Long

    @JvmStatic
    external fun nativeGetNumFiles(handle: Long): Int

    @JvmStatic
    external fun nativeGetEntries(handle: Long): Array<Native7zEntry>?

    @JvmStatic
    external fun nativeExtractToFile(handle: Long, fileIndex: Int, outPath: String): Boolean

    @JvmStatic
    external fun nativeExtractToFd(handle: Long, fileIndex: Int, outFd: Int): Boolean

    @JvmStatic
    external fun nativeExtractToBytes(handle: Long, fileIndex: Int): ByteArray?

    @JvmStatic
    external fun nativeExtractToDirectBuffer(handle: Long, fileIndex: Int): java.nio.ByteBuffer?

    @JvmStatic
    external fun nativePurgeBlockCache(handle: Long)

    @JvmStatic
    external fun nativeVerify(handle: Long, fileIndex: Int): Boolean

    @JvmStatic
    external fun nativeClose(handle: Long)

    @JvmStatic
    external fun nativeDeriveKey(
        passwordBytes: ByteArray,
        saltBytes: ByteArray,
        numCyclesPower: Int
    ): ByteArray?
}

/**
 * Stateful RAII session managing an open 7z archive instance with native solid block caching.
 */
class Native7zArchiveSession private constructor(
    private val handle: Long,
    val entries: List<Native7zEntry>
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val entryMap: Map<String, Native7zEntry>
    private val fileNameMap: Map<String, Native7zEntry>

    init {
        val map = HashMap<String, Native7zEntry>(entries.size * 2)
        val fMap = HashMap<String, Native7zEntry>(entries.size * 2)
        for (e in entries) {
            val norm = e.path.replace('\\', '/').trimStart('/')
            map[norm] = e
            map[e.path] = e
            val fileName = norm.substringAfterLast('/')
            if (!fMap.containsKey(fileName)) {
                fMap[fileName] = e
            }
        }
        entryMap = map
        fileNameMap = fMap
    }

    fun findEntry(entryPath: String): Native7zEntry? {
        val normalized = entryPath.replace('\\', '/').trimStart('/')
        entryMap[normalized]?.let { return it }
        entryMap[entryPath]?.let { return it }

        val nfc = java.text.Normalizer.normalize(normalized, java.text.Normalizer.Form.NFC)
        for (e in entries) {
            val p = e.path.replace('\\', '/').trimStart('/')
            if (p == normalized || java.text.Normalizer.normalize(p, java.text.Normalizer.Form.NFC) == nfc) {
                return e
            }
        }

        val fileName = normalized.substringAfterLast('/')
        return fileNameMap[fileName]
    }

    /**
     * Extracts an entry directly to destination on disk using zero-copy C stream writes.
     */
    fun extractToFile(fileIndex: Int, destination: File): Boolean {
        if (closed.get()) return false
        destination.parentFile?.mkdirs()
        return Native7z.nativeExtractToFile(handle, fileIndex, destination.absolutePath)
    }

    /**
     * Extracts an entry directly to memory (for in-memory thumbnail decoding).
     */
    fun extractToBytes(fileIndex: Int): ByteArray? {
        if (closed.get()) return null
        return Native7z.nativeExtractToBytes(handle, fileIndex)
    }

    /**
     * Extracts an entry directly to a direct [java.nio.ByteBuffer] pointing to native solid block memory.
     */
    fun extractToDirectBuffer(fileIndex: Int): java.nio.ByteBuffer? {
        if (closed.get()) return null
        return Native7z.nativeExtractToDirectBuffer(handle, fileIndex)
    }

    /**
     * Probes entry decryption and solid block decompression without allocating large Java byte arrays.
     */
    fun verifyEntry(fileIndex: Int): Boolean {
        if (closed.get()) return false
        return Native7z.nativeVerify(handle, fileIndex)
    }

    /**
     * Purges cached solid block buffer to reclaim RAM.
     */
    fun purgeCache() {
        if (!closed.get()) {
            Native7z.nativePurgeBlockCache(handle)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            Native7z.nativeClose(handle)
        }
    }

    companion object {
        fun open(path: String, password: String? = null): Native7zArchiveSession? {
            if (!Native7z.isAvailable) return null
            return try {
                val handle = Native7z.nativeOpen(path, password)
                if (handle == 0L) {
                    com.watchpicture.app.util.AppLog.w("Native7z", "nativeOpen returned 0L for $path")
                    return null
                }
                val rawEntries = Native7z.nativeGetEntries(handle)
                if (rawEntries == null) {
                    com.watchpicture.app.util.AppLog.w("Native7z", "nativeGetEntries returned null for $path")
                    Native7z.nativeClose(handle)
                    return null
                }
                com.watchpicture.app.util.AppLog.d("Native7z", "Opened $path with ${rawEntries.size} entries")
                Native7zArchiveSession(handle, rawEntries.toList())
            } catch (e: Throwable) {
                com.watchpicture.app.util.AppLog.e("Native7z", "nativeOpen failed for $path: ${e.message}", e)
                null
            }
        }

        fun openFd(fd: Int, password: String? = null): Native7zArchiveSession? {
            if (!Native7z.isAvailable) return null
            return try {
                val handle = Native7z.nativeOpenFd(fd, password)
                if (handle == 0L) {
                    com.watchpicture.app.util.AppLog.w("Native7z", "nativeOpenFd returned 0L for fd $fd")
                    return null
                }
                val rawEntries = Native7z.nativeGetEntries(handle)
                if (rawEntries == null) {
                    com.watchpicture.app.util.AppLog.w("Native7z", "nativeGetEntries returned null for fd $fd")
                    Native7z.nativeClose(handle)
                    return null
                }
                Native7zArchiveSession(handle, rawEntries.toList())
            } catch (e: Throwable) {
                com.watchpicture.app.util.AppLog.e("Native7z", "nativeOpenFd failed for fd $fd: ${e.message}", e)
                null
            }
        }
    }
}
