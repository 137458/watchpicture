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
            // 这里刻意不 acquire 租约：mapper 交出的是裸 File，没有随 Coil 请求结束释放的钩子，
            // 一旦在此 acquire 就会把该缓存文件永久钉住（LRU 淘汰与 clearAll/clearEncrypted 全部失效，
            // 大图缓存只增不减）。读取窗口的租约由消费方持有：全屏页在 ZoomableImage 的
            // DisposableEffect 中按页面存活期持有，已覆盖 Coil/Telephoto 的整个读取过程。
            return fullFile
        }

        return null
    }
}
