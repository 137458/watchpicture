package com.watchpicture.app.coil

import coil3.map.Mapper
import coil3.request.Options
import com.watchpicture.app.archive.ArchiveDiskCache
import com.watchpicture.app.archive.ThumbnailDiskCache
import java.io.File

/**
 * Maps a [ZipImageSource] to an extracted local [File] if it is already present
 * in [ArchiveDiskCache] or [ThumbnailDiskCache].
 *
 * This enables Telephoto to immediately identify the image model as a local seekable file,
 * which activates native hardware-accelerated tile sub-sampling (BitmapRegionDecoder)
 * and eliminates blurry thumbnail fallbacks and redundant decompression overhead.
 */
class ZipImageMapper(
    private val archiveDiskCache: ArchiveDiskCache? = null,
    private val thumbnailDiskCache: ThumbnailDiskCache? = null
) : Mapper<ZipImageSource, File> {

    override fun map(data: ZipImageSource, options: Options): File? {
        val password = data.password
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.sessionPasswordStore.get(data.zipFile.absolutePath) }.getOrNull()
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.sessionPasswordStore.lastUsedPassword }.getOrNull()

        // Thumbnails stay as ZipImageSource to leverage ZipImageKeyer and ZipImageFetcher's memory Bitmap bypass
        if (data.isThumbnail) {
            return null
        }

        val diskCache = archiveDiskCache
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.archiveDiskCache }.getOrNull()

        val fullFile = diskCache?.get(
            zipFile = data.zipFile,
            entryName = data.entryName,
            password = password
        )
        if (fullFile != null && fullFile.exists() && fullFile.length() > 0) {
            return fullFile
        }

        return null
    }
}
