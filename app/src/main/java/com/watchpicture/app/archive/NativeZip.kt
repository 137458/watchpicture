package com.watchpicture.app.archive

import androidx.annotation.Keep
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Metadata for an entry inside a ZIP parsed by the self-built native mmap engine.
 */
@Keep
data class NativeZipEntry(
    val index: Int,
    val path: String,           // 原始条目名
    val size: Long,             // 解压后大小
    val compressedSize: Long,
    val isDirectory: Boolean
)

/**
 * JNI bindings to the self-built native ZIP engine (调研报告 Z3 项)。
 *
 * 引擎结构：整文件只读 mmap 容器解析（EOCD / ZIP64 EOCD locator / ZIP64 EOCD，
 * 支持 extra field 0x0001 的 64 位 size/offset）+ 整块缓冲 inflate。
 *
 * - inflate 后端：优先使用 vendor 的 libdeflate（MIT，CMake 检测 `zip_engine/`
 *   存在时启用 `NATIVEZIP_USE_LIBDEFLATE`）；未 vendor 时回退 NDK 系统 zlib
 *   裸 deflate 流式 inflate（`inflateInit2(-MAX_WBITS)`），libdeflate 可后续替换。
 * - STORED 条目经 [NativeZipSession.extractToDirectBuffer] 返回零拷贝只读
 *   DirectByteBuffer（直接切片自 mmap 映射区，不复制）；DEFLATE 条目返回的
 *   DirectByteBuffer 指向引擎持有的堆内存，随 session close 一并回收。
 * - direct buffer 在 [NativeZipSession.close] 后失效（mmap unmap / 内存回收），
 *   调用方不得在 close 后继续读取。
 * - CRC32 按需跳过：浏览场景可容忍（调研报告 §7 Z3 明确允许）。
 * - 整包 mmap 仅限 64 位进程使用，32 位进程的大文件 ENOMEM 守卫由调用方负责。
 * - 线程安全：同一 handle 允许多线程并发提取，每次提取独立分配解压状态，
 *   共享的只有只读映射区。
 */
@Keep
object NativeZip {
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
                    System.loadLibrary("nativezip")
                    true
                } catch (t: Throwable) {
                    false
                }
                libraryLoaded = loaded
                loaded
            }
        }

    @JvmStatic
    external fun nativeOpen(path: String): Long

    @JvmStatic
    external fun nativeOpenFd(fd: Int): Long

    @JvmStatic
    external fun nativeGetEntries(handle: Long): Array<NativeZipEntry>?

    @JvmStatic
    external fun nativeExtractToFile(handle: Long, fileIndex: Int, outPath: String): Boolean

    @JvmStatic
    external fun nativeExtractToDirectBuffer(handle: Long, fileIndex: Int): java.nio.ByteBuffer?

    @JvmStatic
    external fun nativeClose(handle: Long)
}

/**
 * Stateful RAII session managing an open native ZIP archive instance.
 * Direct buffers obtained from this session are only valid until [close].
 */
class NativeZipSession private constructor(
    private val handle: Long,
    val entries: List<NativeZipEntry>
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val entryMap: Map<String, NativeZipEntry>
    private val fileNameMap: Map<String, NativeZipEntry>

    init {
        val map = HashMap<String, NativeZipEntry>(entries.size * 2)
        val fMap = HashMap<String, NativeZipEntry>(entries.size * 2)
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

    fun findEntry(entryPath: String): NativeZipEntry? {
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
     * Extracts an entry directly to destination on disk.
     * STORED streams straight out of the mapping with zero intermediate copy.
     * Returns false on any failure (encrypted / unsupported method / corrupt payload / I/O error).
     */
    fun extractToFile(fileIndex: Int, destination: File): Boolean {
        if (closed.get()) return false
        destination.parentFile?.mkdirs()
        return NativeZip.nativeExtractToFile(handle, fileIndex, destination.absolutePath)
    }

    /**
     * Extracts an entry into a direct [java.nio.ByteBuffer].
     * STORED: zero-copy read-only slice of the mmap region (no copy).
     * DEFLATE: engine-owned decompressed memory, reclaimed at [close].
     * The returned buffer must not be written through (STORED slices are read-only
     * mapping memory) and is invalid after [close].
     */
    fun extractToDirectBuffer(fileIndex: Int): java.nio.ByteBuffer? {
        if (closed.get()) return null
        return NativeZip.nativeExtractToDirectBuffer(handle, fileIndex)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            NativeZip.nativeClose(handle)
        }
    }

    companion object {
        fun open(path: String): NativeZipSession? {
            if (!NativeZip.isAvailable) return null
            return try {
                val handle = NativeZip.nativeOpen(path)
                if (handle == 0L) {
                    com.watchpicture.app.util.AppLog.w("NativeZip", "nativeOpen returned 0L for $path")
                    return null
                }
                val rawEntries = NativeZip.nativeGetEntries(handle)
                if (rawEntries == null) {
                    com.watchpicture.app.util.AppLog.w("NativeZip", "nativeGetEntries returned null for $path")
                    NativeZip.nativeClose(handle)
                    return null
                }
                com.watchpicture.app.util.AppLog.d("NativeZip", "Opened $path with ${rawEntries.size} entries")
                NativeZipSession(handle, rawEntries.toList())
            } catch (e: Throwable) {
                com.watchpicture.app.util.AppLog.e("NativeZip", "nativeOpen failed for $path: ${e.message}", e)
                null
            }
        }

        fun openFd(fd: Int): NativeZipSession? {
            if (!NativeZip.isAvailable) return null
            return try {
                val handle = NativeZip.nativeOpenFd(fd)
                if (handle == 0L) {
                    com.watchpicture.app.util.AppLog.w("NativeZip", "nativeOpenFd returned 0L for fd $fd")
                    return null
                }
                val rawEntries = NativeZip.nativeGetEntries(handle)
                if (rawEntries == null) {
                    com.watchpicture.app.util.AppLog.w("NativeZip", "nativeGetEntries returned null for fd $fd")
                    NativeZip.nativeClose(handle)
                    return null
                }
                com.watchpicture.app.util.AppLog.d("NativeZip", "Opened fd $fd with ${rawEntries.size} entries")
                NativeZipSession(handle, rawEntries.toList())
            } catch (e: Throwable) {
                com.watchpicture.app.util.AppLog.e("NativeZip", "nativeOpenFd failed for fd $fd: ${e.message}", e)
                null
            }
        }
    }
}
