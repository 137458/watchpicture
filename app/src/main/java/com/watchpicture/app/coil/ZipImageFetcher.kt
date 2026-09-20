package com.watchpicture.app.coil

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.watchpicture.app.archive.ArchiveDiskCache
import com.watchpicture.app.archive.ZipArchiveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
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
        // Limit max concurrent decompression/decoding threads to 2 to prevent CPU overheating
        private val decompressDispatcher = Dispatchers.IO.limitedParallelism(2)
    }

    override suspend fun fetch(): FetchResult = withContext(decompressDispatcher) {
        val password = data.password
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.sessionPasswordStore.get(data.zipFile.absolutePath) }.getOrNull()
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.sessionPasswordStore.lastUsedPassword }.getOrNull()

        val mimeType = resolveMimeType(data.entryName)

        val diskCache = archiveDiskCache
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.archiveDiskCache }.getOrNull()
        val extractCoord = coordinator
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.archiveExtractionCoordinator }.getOrNull()

        // Branch 1: Thumbnail dedicated pipeline (never dumps full uncompressed raw bytes to flash)
        val thumbCache = thumbnailDiskCache
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.thumbnailDiskCache }.getOrNull()

        if (data.isThumbnail && thumbCache != null) {
            val thumbFile = thumbCache.getOrPut(
                zipFile = data.zipFile,
                entryName = data.entryName,
                targetSizePx = data.targetSizePx,
                password = password
            ) {
                // If full-res file is already cached on disk, read directly from disk (0ms decompression!)
                val cachedFull = diskCache?.get(data.zipFile, data.entryName, password)
                if (cachedFull != null && cachedFull.exists() && cachedFull.length() > 0L) {
                    java.io.FileInputStream(cachedFull)
                } else if (extractCoord != null) {
                    // Extract via coordinator to take advantage of single-pass solid caching
                    val extracted = kotlinx.coroutines.runBlocking {
                        extractCoord.extractHighPriority(data.zipFile, data.entryName, password)
                    }
                    java.io.FileInputStream(extracted)
                } else {
                    zipArchiveManager.getEntryInputStream(
                        file = data.zipFile,
                        entryName = data.entryName,
                        password = password
                    )
                }
            }

            return@withContext SourceFetchResult(
                source = ImageSource(
                    file = thumbFile.toOkioPath(),
                    fileSystem = options.fileSystem
                ),
                mimeType = "image/webp",
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

            return@withContext SourceFetchResult(
                source = ImageSource(
                    file = cachedFile.toOkioPath(),
                    fileSystem = options.fileSystem
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
