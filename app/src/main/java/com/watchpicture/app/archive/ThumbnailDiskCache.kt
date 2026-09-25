package com.watchpicture.app.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

data class ThumbnailResult(
    val file: File,
    val bitmap: Bitmap? = null,
    val lease: Closeable? = null
)

/**
 * Zero-copy InputStream wrapper over a [ByteBuffer].
 */
internal class ByteBufferInputStream(private val buffer: ByteBuffer) : InputStream() {
    private var markPos: Int = buffer.position()

    override fun read(): Int {
        if (!buffer.hasRemaining()) return -1
        return buffer.get().toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!buffer.hasRemaining()) return -1
        val toRead = minOf(len, buffer.remaining())
        buffer.get(b, off, toRead)
        return toRead
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0L
        val toSkip = minOf(n, buffer.remaining().toLong()).toInt()
        buffer.position(buffer.position() + toSkip)
        return toSkip.toLong()
    }

    override fun available(): Int = buffer.remaining()

    override fun markSupported(): Boolean = true

    override fun mark(readlimit: Int) {
        markPos = buffer.position()
    }

    override fun reset() {
        buffer.position(markPos)
    }
}

/**
 * High-performance, dedicated disk cache for downsampled thumbnails.
 *
 * Prevents writing massive 10MB~20MB uncompressed raw bitmaps to flash storage
 * during thumbnail grid loading, drastically reducing flash memory wear,
 * thermal output, and CPU decoding power.
 */
class ThumbnailDiskCache(
    private val directory: File,
    private val maxSizeBytes: Long = DEFAULT_MAX_CACHE_SIZE
) {
    companion object {
        const val DEFAULT_MAX_CACHE_SIZE = 128L * 1024 * 1024 // 128 MB
        private const val STRIPE_COUNT = 64
        private const val BUFFER_SIZE = 32 * 1024
        // Bounds-only decoding only needs the image header prefix; this bounds the heap held
        // while resolving dimensions before a sampled (inSampleSize) streaming decode.
        private const val HEADER_SIZE_BYTES = 64 * 1024
    }

    private val stripeLocks = Array(STRIPE_COUNT) { ReentrantLock() }
    private val globalLock = ReentrantLock()
    private val currentSizeBytes = AtomicLong(-1L)

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        // Establish the baseline from pre-existing cache files so LRU accounting is correct from
        // startup (otherwise trimToSize()'s set() and future addAndGet() would drift). Synchronized
        // under globalLock to stay mutually exclusive with in-flight writes/trims.
        globalLock.withLock {
            currentSizeBytes.set(
                directory.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".tmp") }
                    ?.sumOf { it.length() } ?: 0L
            )
        }
    }

    private fun getLockFor(key: String): ReentrantLock {
        val hash = key.hashCode()
        val index = (hash and 0x7FFFFFFF) % STRIPE_COUNT
        return stripeLocks[index]
    }

    private val asyncFlushScope = CoroutineScope(
        ArchiveDispatchers.backgroundSweepDispatcher + SupervisorJob()
    )
    private val inFlightFlushes = ConcurrentHashMap.newKeySet<String>()

    fun get(
        zipFile: File,
        entryName: String,
        targetSizePx: Int,
        password: String?
    ): File? {
        val entryKey = computeEntryKey(zipFile, entryName, password)
        val targetFile = File(directory, "thumb_${entryKey}_$targetSizePx.webp")
        if (targetFile.exists() && targetFile.length() > 0) {
            return targetFile
        }

        val legacyKey = computeKey(zipFile, entryName, targetSizePx, password)
        val legacyFile = File(directory, "thumb_$legacyKey.webp")
        if (legacyFile.exists() && legacyFile.length() > 0) {
            return legacyFile
        }

        // Elastic fallback: check other available sizes for this entry (e.g. 720 requested, return 360)
        val candidateSizes = listOf(360, 720, 540, 1080, 240, 180)
            .filter { it != targetSizePx }
            .sortedBy { kotlin.math.abs(it - targetSizePx) }
        for (candidate in candidateSizes) {
            val candidateFile = File(directory, "thumb_${entryKey}_$candidate.webp")
            if (candidateFile.exists() && candidateFile.length() > 0) {
                return candidateFile
            }
            val legacyCandidate = File(directory, "thumb_${computeKey(zipFile, entryName, candidate, password)}.webp")
            if (legacyCandidate.exists() && legacyCandidate.length() > 0) {
                return legacyCandidate
            }
        }

        val prefix = "thumb_${entryKey}_"
        val matchingFiles = directory.listFiles { file ->
            file.isFile && file.name.startsWith(prefix) && !file.name.endsWith(".tmp") && file.length() > 0
        }
        if (!matchingFiles.isNullOrEmpty()) {
            val best = matchingFiles.minByOrNull { file ->
                val size = file.name.removePrefix(prefix).substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE
                kotlin.math.abs(size - targetSizePx)
            }
            if (best != null && best.length() > 0) {
                return best
            }
        }

        return null
    }

    typealias ThumbnailResult = com.watchpicture.app.archive.ThumbnailResult

    fun getOrPut(
        zipFile: File,
        entryName: String,
        targetSizePx: Int,
        password: String?,
        openStream: () -> InputStream
    ): File = getOrPutResult(zipFile, entryName, targetSizePx, password, keepBitmapInMemory = false, openStream)
        .also { it.lease?.close() } // This File-only variant does not hand ownership to a reader.
        .file

    fun getOrPutResult(
        zipFile: File,
        entryName: String,
        targetSizePx: Int,
        password: String?,
        keepBitmapInMemory: Boolean = false,
        openStream: () -> InputStream
    ): ThumbnailResult {
        val entryKey = computeEntryKey(zipFile, entryName, password)
        val targetFile = File(directory, "thumb_${entryKey}_$targetSizePx.webp")
        val legacyKey = computeKey(zipFile, entryName, targetSizePx, password)
        val legacyFile = File(directory, "thumb_$legacyKey.webp")

        // Fast path: hand over a lease so the caller (Coil Fetcher) can safely read post-return.
        if (targetFile.exists() && targetFile.length() > 0) {
            return ThumbnailResult(targetFile, null, CacheFileLeases.acquire(targetFile.absolutePath))
        }
        if (legacyFile.exists() && legacyFile.length() > 0) {
            return ThumbnailResult(legacyFile, null, CacheFileLeases.acquire(legacyFile.absolutePath))
        }

        val stripeLock = getLockFor(entryKey)
        return stripeLock.withLock {
            if (targetFile.exists() && targetFile.length() > 0) {
                return@withLock ThumbnailResult(targetFile, null, CacheFileLeases.acquire(targetFile.absolutePath))
            }
            if (legacyFile.exists() && legacyFile.length() > 0) {
                return@withLock ThumbnailResult(legacyFile, null, CacheFileLeases.acquire(legacyFile.absolutePath))
            }

            val tempFile = File(directory, "${targetFile.name}.${System.nanoTime()}.tmp")
            var memoryBitmap: Bitmap? = null
            try {
                // Stream and downsample to compact thumbnail
                openStream().use { input ->
                    memoryBitmap = saveDownsampled(input, tempFile, targetFile, targetSizePx, keepBitmapInMemory)
                }

                if (keepBitmapInMemory && memoryBitmap != null && !memoryBitmap.isRecycled) {
                    return@withLock ThumbnailResult(targetFile, memoryBitmap)
                }

                if (tempFile.exists() && tempFile.length() > 0L) {
                    if (targetFile.exists()) targetFile.delete()
                    val renamed = tempFile.renameTo(targetFile)
                    if (!renamed) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                    // Serialize size accounting, lease acquisition and any triggered trim under
                    // globalLock so a concurrent trimToSize() can neither double-count the add nor
                    // evict the just-written file in the window before ownership is handed over.
                    return@withLock withGlobalLockAndLease(targetFile, memoryBitmap) {
                        currentSizeBytes.addAndGet(targetFile.length())
                    }
                } else {
                    if (tempFile.exists()) tempFile.delete()
                }
            } catch (e: Throwable) {
                if (tempFile.exists()) tempFile.delete()
                memoryBitmap?.recycle()
                throw e
            }

            // tempFile was empty (e.g. compress produced 0 bytes): the memory bitmap (if any) is
            // still returned so a caller can short-circuit to a memory bypass; no lease applies.
            ThumbnailResult(targetFile, memoryBitmap)
        }
    }

    /**
     * Within [globalLock], updates [currentSizeBytes] via [update], triggers a trim if over budget,
     * and acquires a lease on [targetFile] before returning so no concurrent trim can evict it in
     * the window before the caller hands it to a reader. Acquiring inside the lock serializes it
     * against trimToSize(), which also runs under globalLock.
     */
    private fun withGlobalLockAndLease(targetFile: File, bitmap: Bitmap?, update: () -> Unit): ThumbnailResult {
        globalLock.withLock {
            update()
            val lease = CacheFileLeases.acquire(targetFile.absolutePath)
            if (currentSizeBytes.get() > maxSizeBytes) {
                trimToSize()
            }
            return ThumbnailResult(targetFile, bitmap, lease)
        }
    }

    /**
     * Directly decodes and stores a downsampled thumbnail into [ThumbnailDiskCache]
     * from direct [ByteBuffer] without allocating intermediate byte arrays on the Java heap.
     */
    fun getOrPutResultFromByteBuffer(
        zipFile: File,
        entryName: String,
        targetSizePx: Int,
        password: String?,
        keepBitmapInMemory: Boolean = false,
        buffer: ByteBuffer
    ): ThumbnailResult {
        val entryKey = computeEntryKey(zipFile, entryName, password)
        val targetFile = File(directory, "thumb_${entryKey}_$targetSizePx.webp")
        val legacyKey = computeKey(zipFile, entryName, targetSizePx, password)
        val legacyFile = File(directory, "thumb_$legacyKey.webp")

        if (targetFile.exists() && targetFile.length() > 0) {
            return ThumbnailResult(targetFile, null, CacheFileLeases.acquire(targetFile.absolutePath))
        }
        if (legacyFile.exists() && legacyFile.length() > 0) {
            return ThumbnailResult(legacyFile, null, CacheFileLeases.acquire(legacyFile.absolutePath))
        }

        val stripeLock = getLockFor(entryKey)
        return stripeLock.withLock {
            if (targetFile.exists() && targetFile.length() > 0) {
                return@withLock ThumbnailResult(targetFile, null, CacheFileLeases.acquire(targetFile.absolutePath))
            }
            if (legacyFile.exists() && legacyFile.length() > 0) {
                return@withLock ThumbnailResult(legacyFile, null, CacheFileLeases.acquire(legacyFile.absolutePath))
            }

            val tempFile = File(directory, "${targetFile.name}.${System.nanoTime()}.tmp")
            var memoryBitmap: Bitmap? = null
            try {
                memoryBitmap = saveDownsampledByteBuffer(buffer, tempFile, targetFile, targetSizePx, keepBitmapInMemory)

                if (keepBitmapInMemory && memoryBitmap != null && !memoryBitmap.isRecycled) {
                    return@withLock ThumbnailResult(targetFile, memoryBitmap)
                }

                if (tempFile.exists() && tempFile.length() > 0L) {
                    if (targetFile.exists()) targetFile.delete()
                    val renamed = tempFile.renameTo(targetFile)
                    if (!renamed) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                    return@withLock withGlobalLockAndLease(targetFile, memoryBitmap) {
                        currentSizeBytes.addAndGet(targetFile.length())
                    }
                } else {
                    if (tempFile.exists()) tempFile.delete()
                }
            } catch (e: Throwable) {
                if (tempFile.exists()) tempFile.delete()
                memoryBitmap?.recycle()
                throw e
            }

            ThumbnailResult(targetFile, memoryBitmap)
        }
    }

    /**
     * Directly decodes and stores a downsampled thumbnail into [ThumbnailDiskCache]
     * from in-memory byte array.
     */
    fun getOrPutResultFromBytes(
        zipFile: File,
        entryName: String,
        targetSizePx: Int,
        password: String?,
        keepBitmapInMemory: Boolean = false,
        bytes: ByteArray
    ): ThumbnailResult {
        return getOrPutResultFromByteBuffer(
            zipFile = zipFile,
            entryName = entryName,
            targetSizePx = targetSizePx,
            password = password,
            keepBitmapInMemory = keepBitmapInMemory,
            buffer = ByteBuffer.wrap(bytes)
        )
    }

    private fun enqueueAsyncFlush(
        bitmap: Bitmap,
        mime: String,
        hasAlpha: Boolean,
        targetFile: File,
        tempFile: File
    ) {
        if (!inFlightFlushes.add(targetFile.absolutePath)) return
        asyncFlushScope.launch {
            try {
                if (bitmap.isRecycled) return@launch
                compressBitmapToFile(bitmap, mime, hasAlpha, tempFile)

                if (tempFile.exists() && tempFile.length() > 0L) {
                    val lease = CacheFileLeases.acquire(targetFile.absolutePath)
                    try {
                        if (targetFile.exists()) targetFile.delete()
                        val renamed = tempFile.renameTo(targetFile)
                        if (!renamed) {
                            tempFile.copyTo(targetFile, overwrite = true)
                            tempFile.delete()
                        }
                        globalLock.withLock {
                            currentSizeBytes.addAndGet(targetFile.length())
                            if (currentSizeBytes.get() > maxSizeBytes) {
                                trimToSize()
                            }
                        }
                    } finally {
                        lease.close()
                    }
                } else {
                    if (tempFile.exists()) tempFile.delete()
                }
            } catch (_: Throwable) {
                if (tempFile.exists()) tempFile.delete()
            } finally {
                inFlightFlushes.remove(targetFile.absolutePath)
            }
        }
    }

    private fun compressBitmapToFile(
        bitmap: Bitmap,
        mime: String,
        hasAlpha: Boolean,
        targetFile: File
    ) {
        FileOutputStream(targetFile).use { fos ->
            val isTransparent = hasAlpha && bitmap.hasAlpha()
            val format = if (isTransparent) {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            } else {
                Bitmap.CompressFormat.JPEG
            }
            bitmap.compress(format, 75, fos)
            fos.flush()
        }
    }

    private fun saveDownsampledByteBuffer(
        buffer: ByteBuffer,
        tempFile: File,
        targetFile: File,
        targetSizePx: Int,
        keepBitmapInMemory: Boolean
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = ByteBufferInputStream(buffer.duplicate())
        val boundsValid = try {
            BitmapFactory.decodeStream(boundsStream, null, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0
        } catch (_: Throwable) {
            false
        }

        val mime = if (boundsValid) bounds.outMimeType?.lowercase() ?: "" else ""
        val hasAlpha = mime.contains("png") || mime.contains("webp") || mime.contains("gif")

        val bitmap: Bitmap? = if (boundsValid) {
            val maxDim = max(bounds.outWidth, bounds.outHeight)
            var sampleSize = 1
            while ((maxDim / (sampleSize * 2)) >= targetSizePx) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = if (hasAlpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            }
            val decodeStream = ByteBufferInputStream(buffer.duplicate())
            try {
                BitmapFactory.decodeStream(decodeStream, null, decodeOptions)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        if (bitmap != null && !bitmap.isRecycled) {
            if (keepBitmapInMemory) {
                enqueueAsyncFlush(bitmap, mime, hasAlpha, targetFile, tempFile)
                return bitmap
            }
            try {
                compressBitmapToFile(bitmap, mime, hasAlpha, tempFile)
            } finally {
                bitmap.recycle()
            }
            return null
        }

        // Undecodable source: dump buffer directly to disk using FileChannel
        FileOutputStream(tempFile).use { fos ->
            val dup = buffer.duplicate()
            dup.position(0)
            val channel = fos.channel
            while (dup.hasRemaining()) {
                channel.write(dup)
            }
            fos.flush()
        }
        return null
    }

    private fun saveDownsampledFromBytes(
        bytes: ByteArray,
        targetFile: File,
        targetSizePx: Int,
        keepBitmapInMemory: Boolean
    ): Bitmap? {
        val tempFile = File(directory, "${targetFile.name}.${System.nanoTime()}.tmp")
        return saveDownsampledByteBuffer(ByteBuffer.wrap(bytes), tempFile, targetFile, targetSizePx, keepBitmapInMemory)
    }

    private fun saveDownsampled(
        input: InputStream,
        tempFile: File,
        targetFile: File,
        targetSizePx: Int,
        keepBitmapInMemory: Boolean
    ): Bitmap? {
        val head = ByteArray(HEADER_SIZE_BYTES)
        var headLen = 0
        while (headLen < head.size) {
            val n = input.read(head, headLen, head.size - headLen)
            if (n < 0) break
            headLen += n
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsValid = try {
            BitmapFactory.decodeByteArray(head, 0, headLen, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0
        } catch (_: Throwable) {
            false
        }

        val mime = if (boundsValid) bounds.outMimeType?.lowercase() ?: "" else ""
        val hasAlpha = mime.contains("png") || mime.contains("webp") || mime.contains("gif")

        val bitmap: Bitmap? = if (boundsValid) {
            val maxDim = max(bounds.outWidth, bounds.outHeight)
            var sampleSize = 1
            while ((maxDim / (sampleSize * 2)) >= targetSizePx) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = if (hasAlpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            }
            try {
                val full = SequenceInputStream(ByteArrayInputStream(head, 0, headLen), input)
                BitmapFactory.decodeStream(full, null, decodeOptions)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        if (bitmap != null && !bitmap.isRecycled) {
            if (keepBitmapInMemory) {
                enqueueAsyncFlush(bitmap, mime, hasAlpha, targetFile, tempFile)
                return bitmap
            }
            try {
                compressBitmapToFile(bitmap, mime, hasAlpha, tempFile)
            } finally {
                bitmap.recycle()
            }
            return null
        }

        // Undecodable source: mirror the raw entry into the cache, streaming in chunks so
        // heap stays bounded and bytes are never truncated.
        FileOutputStream(tempFile).use { fos ->
            fos.write(head, 0, headLen)
            val buffer = ByteArray(BUFFER_SIZE)
            var n: Int
            while (input.read(buffer).also { n = it } != -1) {
                fos.write(buffer, 0, n)
            }
            fos.flush()
        }
        return null
    }

    /**
     * 统计缓存目录当前占用的字节数（忽略写入中的 .tmp 半成品）。
     */
    fun sizeOnDisk(): Long = directory.listFiles()
        ?.filter { it.isFile && !it.name.endsWith(".tmp") }
        ?.sumOf { it.length() }
        ?: 0L

    fun trimToSize() {
        globalLock.withLock {
            val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
            var totalSize = files.sumOf { it.length() }
            currentSizeBytes.set(totalSize)
            if (totalSize <= maxSizeBytes) return

            val sorted = files
                .filter { !CacheFileLeases.isLeased(it.absolutePath) }
                .sortedBy { it.lastModified() }
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

    private fun computeEntryKey(zipFile: File, entryName: String, password: String?): String {
        val raw = "${zipFile.absolutePath}#${zipFile.lastModified()}#$entryName#${password ?: "none"}"
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun computeKey(zipFile: File, entryName: String, targetSize: Int, password: String?): String {
        val raw = "${zipFile.absolutePath}#${zipFile.lastModified()}#$entryName#sz=$targetSize#${password ?: "none"}"
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
