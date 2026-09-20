package com.watchpicture.app

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.watchpicture.app.archive.ZipArchiveManager
import com.watchpicture.app.coil.ZipImageFetcher
import com.watchpicture.app.coil.ZipImageKeyer
import com.watchpicture.app.security.SessionPasswordStore
import okio.Path.Companion.toOkioPath

class WatchPictureApp : Application(), SingletonImageLoader.Factory {

    val zipArchiveManager: ZipArchiveManager by lazy { ZipArchiveManager() }
    val sessionPasswordStore: SessionPasswordStore by lazy { SessionPasswordStore() }
    val preferencesRepository: com.watchpicture.app.storage.PreferencesRepository by lazy {
        com.watchpicture.app.storage.PreferencesRepository(this)
    }
    val passwordBookRepository: com.watchpicture.app.storage.PasswordBookRepository by lazy {
        com.watchpicture.app.storage.PasswordBookRepository(this)
    }
    val archiveFileResolver: com.watchpicture.app.archive.ArchiveFileResolver by lazy {
        com.watchpicture.app.archive.ArchiveFileResolver(zipArchiveManager, sessionPasswordStore)
    }
    val archiveDiskCache: com.watchpicture.app.archive.ArchiveDiskCache by lazy {
        com.watchpicture.app.archive.ArchiveDiskCache(java.io.File(cacheDir, "archive_cache"))
    }
    val thumbnailDiskCache: com.watchpicture.app.archive.ThumbnailDiskCache by lazy {
        com.watchpicture.app.archive.ThumbnailDiskCache(java.io.File(cacheDir, "thumbnail_cache"))
    }

    val externalArchiveFlow = kotlinx.coroutines.flow.MutableSharedFlow<android.net.Uri>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    fun dispatchExternalArchive(uri: android.net.Uri) {
        externalArchiveFlow.tryEmit(uri)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val stackTrace = android.util.Log.getStackTraceString(e)
            android.util.Log.e("WatchPictureCrash", "Uncaught exception in thread ${t.name}:\n$stackTrace")

            try {
                val logDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val logFile = java.io.File(logDir, "watchpicture_crash.log")
                logFile.writeText("Time: ${java.util.Date()}\nThread: ${t.name}\nException:\n$stackTrace\n")
            } catch (_: Throwable) {
                try {
                    val fallbackFile = java.io.File(getExternalFilesDir(null), "watchpicture_crash.log")
                    fallbackFile.writeText("Time: ${java.util.Date()}\nThread: ${t.name}\nException:\n$stackTrace\n")
                } catch (_: Throwable) {}
            }

            defaultHandler?.uncaughtException(t, e)
        }

        // Clean up any stale temporary files from previous sessions
        try {
            val coilCache = java.io.File(cacheDir, "coil_zip_cache")
            if (coilCache.exists()) {
                coilCache.listFiles()?.forEach { it.delete() }
            }
        } catch (_: Throwable) {}

        SingletonImageLoader.setSafe { newImageLoader(this) }
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(ZipImageKeyer())
                add(ZipImageFetcher.Factory(zipArchiveManager, archiveDiskCache, thumbnailDiskCache))
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(coil3.gif.AnimatedImageDecoder.Factory())
                } else {
                    add(coil3.gif.GifDecoder.Factory())
                }
                add(coil3.svg.SvgDecoder.Factory())
            }
            .memoryCache {
                coil3.memory.MemoryCache.Builder()
                    .maxSizePercent(context, 0.35)
                    .build()
            }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil_disk_cache").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    companion object {
        lateinit var instance: WatchPictureApp
            private set
    }
}
