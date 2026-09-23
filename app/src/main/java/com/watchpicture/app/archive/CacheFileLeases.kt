package com.watchpicture.app.archive

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Registry of in-flight leases on files stored in [ArchiveDiskCache] and [ThumbnailDiskCache].
 *
 * A cache file handed to a consumer (e.g. Coil's `ImageSource`) is only safe to read while it
 * still exists. The fetcher returns a [java.io.File] path, and Coil opens/decodes it
 * asynchronously afterwards. In that window LRU eviction ([ArchiveDiskCache.trimToSize],
 * [ThumbnailDiskCache.trimToSize]) or cleanup ([ArchiveDiskCache.clearEncrypted],
 * [ArchiveDiskCache.clearAll], [ThumbnailDiskCache.clearAll]) could delete the file, causing a
 * `FileNotFoundException` / blank image.
 *
 * Caches therefore consult [isLeased] and skip files that are still being read. Leases are
 * reference-counted: acquire before returning the file, release by closing the returned handle
 * (wired to Coil's `ImageSource(closeable = ...)`, which is closed by `EngineInterceptor`).
 */
object CacheFileLeases {

    private val refCounts = ConcurrentHashMap<String, AtomicInteger>()

    /** Acquires a lease on the file at [path]. Close the returned handle to release it. */
    fun acquire(path: String): Closeable {
        // Increment inside compute() so a concurrent release() cannot remove the entry between
        // the lookup and the increment (which would silently drop the lease).
        refCounts.compute(path) { _, count ->
            (count ?: AtomicInteger(0)).also { it.incrementAndGet() }
        }
        // Guard against double-close: Coil's FileImageSource.close() is not idempotent and may
        // close the same ImageSource more than once, which would otherwise over-decrement.
        val released = AtomicBoolean(false)
        return Closeable { if (released.compareAndSet(false, true)) release(path) }
    }

    private fun release(path: String) {
        refCounts.computeIfPresent(path) { _, count ->
            if (count.decrementAndGet() <= 0) null else count
        }
    }

    /** Whether at least one lease is currently held on the file at [path]. */
    fun isLeased(path: String): Boolean = (refCounts[path]?.get() ?: 0) > 0

    /** Clears all outstanding leases. Intended for tests only. */
    fun clear() {
        refCounts.clear()
    }
}
