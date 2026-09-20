package com.watchpicture.app.archive

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Metadata for an entry inside a 7z archive parsed by the native 7-Zip C SDK.
 */
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
    external fun nativeOpen(path: String): Long

    @JvmStatic
    @Throws(IOException::class)
    external fun nativeOpenFd(fd: Int): Long

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
    external fun nativePurgeBlockCache(handle: Long)

    @JvmStatic
    external fun nativeClose(handle: Long)
}

/**
 * Stateful RAII session managing an open 7z archive instance with native solid block caching.
 */
class Native7zArchiveSession private constructor(
    private val handle: Long,
    val entries: List<Native7zEntry>
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val entryMap: Map<String, Native7zEntry> = entries.associateBy { it.path.trimStart('/') }

    fun findEntry(entryPath: String): Native7zEntry? {
        val normalized = entryPath.trimStart('/')
        return entryMap[normalized]
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
        fun open(path: String): Native7zArchiveSession? {
            if (!Native7z.isAvailable) return null
            return try {
                val handle = Native7z.nativeOpen(path)
                if (handle == 0L) return null
                val rawEntries = Native7z.nativeGetEntries(handle) ?: emptyArray()
                Native7zArchiveSession(handle, rawEntries.toList())
            } catch (e: Throwable) {
                null
            }
        }

        fun openFd(fd: Int): Native7zArchiveSession? {
            if (!Native7z.isAvailable) return null
            return try {
                val handle = Native7z.nativeOpenFd(fd)
                if (handle == 0L) return null
                val rawEntries = Native7z.nativeGetEntries(handle) ?: emptyArray()
                Native7zArchiveSession(handle, rawEntries.toList())
            } catch (e: Throwable) {
                null
            }
        }
    }
}
