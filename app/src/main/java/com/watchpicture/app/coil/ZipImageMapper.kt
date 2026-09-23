package com.watchpicture.app.coil

import coil3.map.Mapper
import coil3.request.Options
import com.watchpicture.app.archive.ArchiveDiskCache
import com.watchpicture.app.archive.CacheFileLeases
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
        // Cache keys must derive from the pack's own canonical password only. The global
        // lastUsedPassword is intentionally not used here (it may belong to another pack).
        val password = data.password
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.sessionPasswordStore.get(data.zipFile.absolutePath) }.getOrNull()

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
            // Acquire a lease before handing the cache file to Coil/Telephoto. The card file may
            // otherwise be LRU-evicted during the decode time window, yielding a blank image /
            // FileNotFoundException. The lease is held for the process lifetime (the mapper returns a
            // plain File with no Coil release hook) and is cleared on process restart; this is a
            // deliberate trade-off to guarantee a stable local file for subsampling.
            CacheFileLeases.acquire(fullFile.absolutePath)
            return fullFile
        }

        return null
    }
}
