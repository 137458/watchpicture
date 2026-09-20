package com.watchpicture.app.coil

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.watchpicture.app.archive.ZipArchiveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.Locale

/**
 * Custom Coil 3 Fetcher that streams decompressed image bytes directly
 * from ZipArchiveManager and serves them via an AutoCloseable seekable file source.
 */
class ZipImageFetcher(
    private val data: ZipImageSource,
    private val options: Options,
    private val zipArchiveManager: ZipArchiveManager
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val password = data.password
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.sessionPasswordStore.get(data.zipFile.absolutePath) }.getOrNull()
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.sessionPasswordStore.lastUsedPassword }.getOrNull()

        val inputStream = zipArchiveManager.getEntryInputStream(
            file = data.zipFile,
            entryName = data.entryName,
            password = password
        )

        val cacheBase = options.context.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val cacheDir = File(cacheBase, "coil_zip_cache").apply { mkdirs() }
        val tempFile = File.createTempFile("zip_img_", ".tmp", cacheDir)

        try {
            tempFile.outputStream().use { output ->
                inputStream.use { input ->
                    input.copyTo(output)
                }
            }

            val mimeType = resolveMimeType(data.entryName)

            SourceFetchResult(
                source = ImageSource(
                    file = tempFile.toOkioPath(),
                    fileSystem = options.fileSystem,
                    closeable = AutoCloseable {
                        tempFile.delete()
                    }
                ),
                mimeType = mimeType,
                dataSource = DataSource.DISK
            )
        } catch (e: Throwable) {
            tempFile.delete()
            throw e
        }
    }

    private fun resolveMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "avif" -> "image/avif"
            "heic", "heif" -> "image/heif"
            else -> "image/jpeg"
        }
    }

    class Factory(
        private val zipArchiveManager: ZipArchiveManager
    ) : Fetcher.Factory<ZipImageSource> {
        override fun create(
            data: ZipImageSource,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher {
            return ZipImageFetcher(data, options, zipArchiveManager)
        }
    }
}
