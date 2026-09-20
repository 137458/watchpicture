package com.watchpicture.app.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

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

    fun get(
        zipFile: File,
        entryName: String,
        targetSizePx: Int,
        password: String?
    ): File? {
        val cacheKey = computeKey(zipFile, entryName, targetSizePx, password)
        val targetFile = File(directory, "thumb_$cacheKey.webp")
        if (targetFile.exists() && targetFile.length() > 0) {
            targetFile.setLastModified(System.currentTimeMillis())
            return targetFile
        }
        return null
    }

    fun getOrPut(
        zipFile: File,
        entryName: String,
        targetSizePx: Int,
        password: String?,
        openStream: () -> InputStream
    ): File {
        val cacheKey = computeKey(zipFile, entryName, targetSizePx, password)
        val targetFile = File(directory, "thumb_$cacheKey.webp")

        // Fast path
        if (targetFile.exists() && targetFile.length() > 0) {
            targetFile.setLastModified(System.currentTimeMillis())
            return targetFile
        }

        val stripeLock = getLockFor(cacheKey)
        stripeLock.withLock {
            if (targetFile.exists() && targetFile.length() > 0) {
                targetFile.setLastModified(System.currentTimeMillis())
                return targetFile
            }

            val tempFile = File(directory, "${targetFile.name}.${System.nanoTime()}.tmp")
            try {
                // Stream and downsample to compact thumbnail
                openStream().use { input ->
                    saveDownsampled(input, tempFile, targetSizePx)
                }

                if (tempFile.exists() && tempFile.length() > 0L) {
                    if (targetFile.exists()) targetFile.delete()
                    val renamed = tempFile.renameTo(targetFile)
                    if (!renamed) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                    val added = targetFile.length()
                    val newTotal = currentSizeBytes.addAndGet(added)
                    if (newTotal > maxSizeBytes) {
                        trimToSize()
                    }
                } else {
                    if (tempFile.exists()) tempFile.delete()
                }
            } catch (e: Throwable) {
                if (tempFile.exists()) tempFile.delete()
                throw e
            }

            return targetFile
        }
    }

    private fun saveDownsampled(input: InputStream, targetFile: File, targetSizePx: Int) {
        val buffered = if (input.markSupported()) input else java.io.BufferedInputStream(input, 128 * 1024)
        buffered.mark(128 * 1024)

        var bitmap: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(buffered, null, options)

            if (options.outWidth > 0 && options.outHeight > 0) {
                runCatching { buffered.reset() }

                val maxDim = max(options.outWidth, options.outHeight)
                var sampleSize = 1
                while ((maxDim / (sampleSize * 2)) >= targetSizePx) {
                    sampleSize *= 2
                }

                val mime = options.outMimeType?.lowercase() ?: ""
                val hasAlpha = mime.contains("png") || mime.contains("webp") || mime.contains("gif")

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = if (hasAlpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
                }
                bitmap = BitmapFactory.decodeStream(buffered, null, decodeOptions)
            }
        } catch (_: Throwable) {
            bitmap = null
        }

        FileOutputStream(targetFile).use { fos ->
            if (bitmap != null) {
                try {
                    // Compress as compact WebP/JPEG thumbnail
                    val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        Bitmap.CompressFormat.JPEG
                    }
                    bitmap.compress(format, 80, fos)
                } finally {
                    bitmap.recycle()
                }
            } else {
                // Fallback direct copy if BitmapFactory fails (e.g. SVG or JVM unit test)
                runCatching { buffered.reset() }
                buffered.copyTo(fos, bufferSize = BUFFER_SIZE)
            }
            fos.flush()
        }
    }

    fun trimToSize() {
        globalLock.withLock {
            val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
            var totalSize = files.sumOf { it.length() }
            currentSizeBytes.set(totalSize)
            if (totalSize <= maxSizeBytes) return

            val sorted = files.sortedBy { it.lastModified() }
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
            directory.listFiles()?.forEach { it.delete() }
            currentSizeBytes.set(0L)
        }
    }

    private fun computeKey(zipFile: File, entryName: String, targetSize: Int, password: String?): String {
        val raw = "${zipFile.absolutePath}#${zipFile.lastModified()}#$entryName#sz=$targetSize#${password ?: "none"}"
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
