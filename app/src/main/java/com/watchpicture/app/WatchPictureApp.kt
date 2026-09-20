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

    override fun onCreate() {
        super.onCreate()
        instance = this
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
