package com.watchpicture.app.archive

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * memfd 大图管线（调研 D2 项）。
 *
 * 全尺寸图片的冷路径解压进匿名内存文件（`Os.memfd_create`），经 `/proc/self/fd/<fd>`
 * 以文件态 ImageSource 交给 Coil/Telephoto——BRD 瓦片子采样照常 mmap 该路径，语义与
 * 磁盘缓存文件一致，但省去 30~80ms 的顺序落盘写与闪存磨损。磁盘缓存命中（预取已填）
 * 时优先用磁盘文件，本管线只接管未命中条目，避免重复解压落盘。
 *
 * 配额：仅当前页±预读页持有（条目数 + 字节双上限 LRU），超限淘汰即释放内存；
 * 低内存回调（onTrimMemory）全量释放。API < 30 或任何系统调用失败返回 null，
 * 调用方回退磁盘缓存路径。密钥只以单向摘要参与缓存键，不落任何盘（密码不落盘）。
 */
object MemFdPipeline {

    /** 条目数上限：当前浏览页 ± 预读页。 */
    const val MAX_ENTRIES = 3

    /** 字节预算上限：memfd 为 tmpfs，全额计入 RAM；单条目超出预算时仍保留自身。 */
    const val MAX_TOTAL_BYTES: Long = 160L * 1024 * 1024

    /**
     * 读取句柄。读取方（okio/BRD）各自 open(`/proc/self/fd/<n>`) 得到独立 fd，
     * 因此本句柄的 close 是空操作；存活期由管线 LRU 持有期决定（淘汰即失效）。
     */
    class MemFdHandle internal constructor(
        val path: String,
        val sizeBytes: Long
    ) : Closeable {
        override fun close() = Unit
    }

    private class Entry(
        val key: String,
        val memfd: FileDescriptor,
        /** dup 出的 PFD：其数字 fd 必须存活到淘汰，否则 /proc/self/fd/<n> 可能被复用漂移。 */
        val pfd: ParcelFileDescriptor,
        val sizeBytes: Long
    ) : Closeable {
        val handle = MemFdHandle("/proc/self/fd/${pfd.fd}", sizeBytes)

        override fun close() {
            runCatching { pfd.close() }
            runCatching { Os.close(memfd) }
        }
    }

    val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= 30

    private val lock = ReentrantLock()
    private val lru = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            if (eldest == null) return false
            val totalBytes = values.sumOf { it.sizeBytes }
            if (size <= MAX_ENTRIES && totalBytes <= MAX_TOTAL_BYTES) return false
            eldest.value.close()
            return true
        }
    }

    /**
     * Retrieves a cached memfd entry or creates one by streaming decompressed bytes via
     * [producer]. Returns null when the pipeline is unavailable or creation failed —
     * callers must fall back to the disk-cache path. Concurrent same-key creations may
     * both run; the loser is discarded under the lock (mirrors ArchiveDiskCache dedupe).
     */
    fun getOrPut(cacheKey: String, producer: (OutputStream) -> Unit): MemFdHandle? {
        if (!isAvailable) return null
        lock.withLock { lru[cacheKey]?.let { return it.handle } }

        val entry = createEntry(cacheKey, producer) ?: return null
        lock.withLock {
            val existing = lru[cacheKey]
            if (existing != null) {
                entry.close()
                return existing.handle
            }
            lru[cacheKey] = entry
            return entry.handle
        }
    }

    private fun createEntry(cacheKey: String, producer: (OutputStream) -> Unit): Entry? {
        return try {
            val memfd = Os.memfd_create("watchpic", 0)
            try {
                FileOutputStream(memfd).use { out ->
                    producer(out)
                    out.flush()
                }
                val size = Os.fstat(memfd).st_size
                if (size <= 0L) {
                    runCatching { Os.close(memfd) }
                    return null
                }
                val pfd = ParcelFileDescriptor.dup(memfd)
                Entry(cacheKey, memfd, pfd, size)
            } catch (t: Throwable) {
                runCatching { Os.close(memfd) }
                throw t
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 低内存回调：全量释放（当前页释放后由 Coil 重新请求，回退磁盘缓存路径）。 */
    fun closeAll() {
        lock.withLock {
            for (entry in lru.values) entry.close()
            lru.clear()
        }
    }

    /** 缓存键：完整 SHA-256 摘要参与（与 ArchiveDiskCache 同口径），绝不驻留明文密码。 */
    fun cacheKey(zipFile: java.io.File, entryName: String, password: String?): String {
        val pwdToken = if (password.isNullOrEmpty()) {
            "none"
        } else {
            MessageDigest.getInstance("SHA-256")
                .digest(password.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        val raw = "${zipFile.absolutePath}#${zipFile.lastModified()}#$entryName#$pwdToken"
        return MessageDigest.getInstance("MD5")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
