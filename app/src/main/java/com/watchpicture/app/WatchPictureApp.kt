package com.watchpicture.app

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.watchpicture.app.archive.ZipArchiveManager
import com.watchpicture.app.coil.ZipImageFetcher
import com.watchpicture.app.coil.ZipImageKeyer
import com.watchpicture.app.security.SessionPasswordStore

class WatchPictureApp : Application(), SingletonImageLoader.Factory {

    val zipArchiveManager: ZipArchiveManager by lazy { ZipArchiveManager() }
    val sessionPasswordStore: SessionPasswordStore by lazy { SessionPasswordStore() }
    val preferencesRepository: com.watchpicture.app.storage.PreferencesRepository by lazy {
        com.watchpicture.app.storage.PreferencesRepository(this)
    }
    val archiveFileResolver: com.watchpicture.app.archive.ArchiveFileResolver by lazy {
        com.watchpicture.app.archive.ArchiveFileResolver(zipArchiveManager, sessionPasswordStore)
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
            android.util.Log.e("WatchPictureCrash", "Uncaught exception in thread ${t.name}: ${e.message}", e)
            defaultHandler?.uncaughtException(t, e)
        }
        SingletonImageLoader.setSafe { newImageLoader(this) }
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(ZipImageKeyer())
                add(ZipImageFetcher.Factory(zipArchiveManager))
            }
            .build()
    }

    companion object {
        lateinit var instance: WatchPictureApp
            private set
    }
}
