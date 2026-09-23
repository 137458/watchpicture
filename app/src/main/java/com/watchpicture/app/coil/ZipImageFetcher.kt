package com.watchpicture.app.coil

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.watchpicture.app.archive.ArchiveDiskCache
import com.watchpicture.app.archive.CacheFileLeases
import com.watchpicture.app.archive.ZipArchiveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Custom Coil 3 Fetcher that streams decompressed image bytes directly
 * from ZipArchiveManager and serves them via an AutoCloseable seekable file source.
 *
 * When an [ArchiveDiskCache] is present, decompressed entries are streamed directly
 * to a dedicated disk cache file and returned as a native [DataSource.DISK] FileSource.
 * This completely avoids large heap buffer allocations for 10MB+ files and enables
 * sub-sampling tile decoders (BitmapRegionDecoder) to seek directly on disk.
 */
class ZipImageFetcher(
    private val data: ZipImageSource,
    private val options: Options,
    private val zipArchiveManager: ZipArchiveManager,
    private val archiveDiskCache: ArchiveDiskCache? = null,
    private val thumbnailDiskCache: com.watchpicture.app.archive.ThumbnailDiskCache? = null,
    private val coordinator: com.watchpicture.app.archive.ArchiveExtractionCoordinator? = null
) : Fetcher {

    companion object {
        // Run decompression and decoding exclusively on energy-efficient LITTLE CPU cores
        private val decompressDispatcher = com.watchpicture.app.archive.ArchiveDispatchers.decompressDispatcher
    }

    override suspend fun fetch(): FetchResult = withContext(decompressDispatcher) {
        val t0 = System.currentTimeMillis()
        // Cache keys must derive from the pack's own canonical password only. The global
        // lastUsedPassword is intentionally not used here (it may belong to another pack).
        val password = data.password
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.sessionPasswordStore.get(data.zipFile.absolutePath) }.getOrNull()

        val mimeType = resolveMimeType(data.entryName)

        val diskCache = archiveDiskCache
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.archiveDiskCache }.getOrNull()
        val extractCoord = coordinator
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.archiveExtractionCoordinator }.getOrNull()

        // Branch 1: Thumbnail dedicated pipeline (never dumps full uncompressed raw bytes to flash for plain ZIPs)
        val thumbCache = thumbnailDiskCache
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.thumbnailDiskCache }.getOrNull()

        if (data.isThumbnail && thumbCache != null) {
            val existingThumb = thumbCache.get(data.zipFile, data.entryName, data.targetSizePx, password)
            if (existingThumb != null) {
                val thumbLease = leaseVerified(existingThumb)
                if (thumbLease != null) {
                    com.watchpicture.app.util.AppLog.i("Thumbnail", "Disk hit for ${data.entryName} in ${System.currentTimeMillis() - t0}ms")
                    return@withContext SourceFetchResult(
                        source = ImageSource(
                            file = existingThumb.toOkioPath(),
                            fileSystem = options.fileSystem,
                            closeable = thumbLease
                        ),
                        mimeType = resolveFileMimeType(existingThumb),
                        dataSource = DataSource.DISK
                    )
                }
                // Evicted in the race window between the cache lookup and here -> re-extract below.
            }

            val thumbResult = if (extractCoord != null && ZipArchiveManager.isSevenZFile(data.zipFile)) {
                extractCoord.extractThumbnailDirectResult(
                    file = data.zipFile,
                    entryName = data.entryName,
                    targetSizePx = data.targetSizePx,
                    password = password,
                    thumbnailDiskCache = thumbCache,
                    keepBitmapInMemory = true
                )
            } else {
                val cachedFull = diskCache?.get(data.zipFile, data.entryName, password)
                thumbCache.getOrPutResult(
                    zipFile = data.zipFile,
                    entryName = data.entryName,
                    targetSizePx = data.targetSizePx,
                    password = password,
                    keepBitmapInMemory = true
                ) {
                    if (cachedFull != null && cachedFull.exists() && cachedFull.length() > 0L) {
                        java.io.FileInputStream(cachedFull)
                    } else {
                        // Stream directly into downsampler, 0 full-res disk dump!
                        zipArchiveManager.getEntryInputStream(
                            file = data.zipFile,
                            entryName = data.entryName,
                            password = password
                        )
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - t0
            if (thumbResult.bitmap != null && !thumbResult.bitmap.isRecycled) {
                com.watchpicture.app.util.AppLog.i("Thumbnail", "Memory bypass for ${data.entryName} in ${elapsed}ms")
                // The decoded Bitmap is returned as an ImageFetchResult; Coil's EngineInterceptor
                // automatically writes it to the memory cache under the request's memory cache key
                // (which matches ZipImageKeyer), so a manual memory-cache write here is redundant.
                // The disk file is not handed to Coil, so release its freshly-acquired lease now.
                thumbResult.lease?.close()
                return@withContext ImageFetchResult(
                    image = thumbResult.bitmap.asImage(),
                    isSampled = true,
                    dataSource = DataSource.MEMORY
                )
            }

            val thumbFile = thumbResult.file
            // getOrPutResult already handed ownership of the lease (acquired before returning so the
            // just-written file cannot be evicted by a concurrent trim). Fall back to acquiring here
            // for coordinator fast-paths that return the file without a lease.
            val thumbFileLease = thumbResult.lease ?: leaseVerified(thumbFile)
                ?: throw IOException("Thumbnail extraction produced no file for '${data.entryName}'")
            com.watchpicture.app.util.AppLog.i("Thumbnail", "Disk extracted for ${data.entryName} in ${elapsed}ms")
            return@withContext SourceFetchResult(
                source = ImageSource(
                    file = thumbFile.toOkioPath(),
                    fileSystem = options.fileSystem,
                    closeable = thumbFileLease
                ),
                mimeType = resolveFileMimeType(thumbFile),
                dataSource = DataSource.DISK
            )
        }

        // Branch 2: Full-res viewing and Telephoto tile subsampling pipeline
        if (diskCache != null) {
            val cachedFile = if (extractCoord != null) {
                extractCoord.extractHighPriority(data.zipFile, data.entryName, password)
            } else {
                diskCache.getOrPut(
                    zipFile = data.zipFile,
                    entryName = data.entryName,
                    password = password
                ) {
                    zipArchiveManager.getEntryInputStream(
                        file = data.zipFile,
                        entryName = data.entryName,
                        password = password
                    )
                }
            }

            val cachedFileLease = leaseVerified(cachedFile)
                ?: throw IOException("Cached entry evicted before decode: '${data.entryName}'")
            return@withContext SourceFetchResult(
                source = ImageSource(
                    file = cachedFile.toOkioPath(),
                    fileSystem = options.fileSystem,
                    closeable = cachedFileLease
                ),
                mimeType = mimeType,
                dataSource = DataSource.DISK
            )
        }

        // In-memory fallback if disk cache is unavailable
        val inputStream = zipArchiveManager.getEntryInputStream(
            file = data.zipFile,
            entryName = data.entryName,
            password = password
        )

        val buffer = okio.Buffer()
        inputStream.use { input ->
            buffer.readFrom(input)
        }

        SourceFetchResult(
            source = ImageSource(
                source = buffer,
                fileSystem = options.fileSystem
            ),
            mimeType = mimeType,
            dataSource = DataSource.MEMORY
        )
    }

    /**
     * Acquires a lease on [file] and verifies it still exists. Returns null (releasing the lease)
     * when the file was evicted in the race window between the cache lookup and here, so the
     * caller can re-extract instead of handing Coil a dead path.
     */
    private fun leaseVerified(file: File): java.io.Closeable? {
        val lease = CacheFileLeases.acquire(file.absolutePath)
        if (file.exists() && file.length() > 0L) return lease
        lease.close()
        return null
    }

    private fun resolveMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg", "jfif", "pjpeg", "pjp" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "avif" -> "image/avif"
            "heic", "heif" -> "image/heif"
            "svg" -> "image/svg+xml"
            "tiff", "tif" -> "image/tiff"
            "ico" -> "image/x-icon"
            else -> "image/jpeg"
        }
    }

    /**
     * Sniffs the real on-disk encoding of a cached thumbnail file by its magic bytes.
     *
     * [com.watchpicture.app.archive.ThumbnailDiskCache] always names the file `*.webp`, but the
     * actual content is WebP (API 30+), JPEG (API < 30), or the raw original entry bytes when the
     * source could not be downsampled. Reporting a MIME type that matches the real content keeps
     * Coil's decoder selection correct. Falls back to "image/webp" when the format is unknown.
     */
    private fun resolveFileMimeType(file: File): String {
        return runCatching {
            val header = ByteArray(12)
            val read = java.io.FileInputStream(file).use { it.read(header) }
            when {
                read >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() ->
                    "image/jpeg"
                read >= 8 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() -> "image/png"
                read >= 12 && header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                    header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
                    header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
                    header[10] == 0x42.toByte() && header[11] == 0x50.toByte() -> "image/webp"
                read >= 4 && header[0] == 0x47.toByte() && header[1] == 0x49.toByte() &&
                    header[2] == 0x46.toByte() && header[3] == 0x38.toByte() -> "image/gif"
                read >= 2 && header[0] == 0x42.toByte() && header[1] == 0x4D.toByte() -> "image/bmp"
                else -> "image/webp"
            }
        }.getOrDefault("image/webp")
    }

    class Factory(
        private val zipArchiveManager: ZipArchiveManager,
        private val archiveDiskCache: ArchiveDiskCache? = null,
        private val thumbnailDiskCache: com.watchpicture.app.archive.ThumbnailDiskCache? = null,
        private val coordinator: com.watchpicture.app.archive.ArchiveExtractionCoordinator? = null
    ) : Fetcher.Factory<ZipImageSource> {
        override fun create(
            data: ZipImageSource,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher {
            return ZipImageFetcher(data, options, zipArchiveManager, archiveDiskCache, thumbnailDiskCache, coordinator)
        }
    }
}
