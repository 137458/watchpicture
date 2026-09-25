package com.watchpicture.app

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.watchpicture.app.archive.ZipArchiveManager
import com.watchpicture.app.coil.ZipImageFetcher
import com.watchpicture.app.coil.ZipImageKeyer
import com.watchpicture.app.security.SessionPasswordStore
import com.watchpicture.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchPictureApp : Application(), SingletonImageLoader.Factory {

    /**
     * 应用级后台作用域：承载缓存维护等不绑定任何界面生命周期的长任务。
     */
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    val powerThermalManager: com.watchpicture.app.archive.PowerThermalManager by lazy {
        com.watchpicture.app.archive.PowerThermalManager(this)
    }
    val archiveExtractionCoordinator: com.watchpicture.app.archive.ArchiveExtractionCoordinator by lazy {
        com.watchpicture.app.archive.ArchiveExtractionCoordinator(zipArchiveManager, archiveDiskCache, powerThermalManager)
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
        org.apache.commons.compress.archivers.sevenz.CachedAES256SHA256Decoder.install()
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
            val legacyDiskCache = java.io.File(cacheDir, "coil_disk_cache")
            if (legacyDiskCache.exists()) {
                legacyDiskCache.deleteRecursively()
            }
        } catch (_: Throwable) {}

        SingletonImageLoader.setSafe { newImageLoader(this) }

        scheduleAutoCacheClean()
    }

    /**
     * 统计全部磁盘缓存占用：归档条目缓存 + 缩略图缓存 + SAF 归档落盘副本。
     */
    suspend fun totalCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        archiveDiskCache.sizeOnDisk() +
            thumbnailDiskCache.sizeOnDisk() +
            archiveFileResolver.archiveCacheSizeBytes(this@WatchPictureApp)
    }

    /**
     * 清空全部磁盘缓存，返回实际释放的字节数。
     * SAF 归档落盘副本被清掉后，相关图包在下次打开时会按需重新落盘。
     */
    suspend fun clearAllCaches(): Long = withContext(Dispatchers.IO) {
        val before = totalCacheSizeBytes()
        archiveDiskCache.clearAll()
        thumbnailDiskCache.clearAll()
        archiveFileResolver.clearArchiveCache(this@WatchPictureApp)
        (before - totalCacheSizeBytes()).coerceAtLeast(0L)
    }

    /**
     * 按容量预算裁剪磁盘缓存。归档条目缓存与缩略图缓存在写入时已自淘汰，
     * 这里额外处理此前完全没有上限的 SAF 归档落盘副本。
     */
    fun scheduleAutoCacheClean() {
        maintenanceScope.launch {
            try {
                if (!preferencesRepository.autoCleanCacheFlow.first()) return@launch
                archiveDiskCache.trimToSize()
                thumbnailDiskCache.trimToSize()
                archiveFileResolver.trimArchiveCache(this@WatchPictureApp)
            } catch (e: Throwable) {
                AppLog.e("CacheClean", "自动清理缓存失败", e)
            }
        }
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(ZipImageKeyer())
                add(com.watchpicture.app.coil.ZipImageMapper(archiveDiskCache, thumbnailDiskCache))
                add(ZipImageFetcher.Factory(zipArchiveManager, archiveDiskCache, thumbnailDiskCache, archiveExtractionCoordinator))
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(coil3.gif.AnimatedImageDecoder.Factory())
                } else {
                    add(coil3.gif.GifDecoder.Factory())
                }
                add(coil3.svg.SvgDecoder.Factory())
            }
            .memoryCache {
                coil3.memory.MemoryCache.Builder()
                    .maxSizePercent(context, 0.40)
                    .build()
            }
            .build()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            val imageLoader = SingletonImageLoader.get(this)
            when {
                // 严重度最高：进程完全后台(COMPLETE) —— 清空全部内存缓存、句柄、7z 会话并裁剪磁盘缓存
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                    // Extreme memory pressure: flush all memory caches, file handles, 7z sessions, and trim disk caches
                    imageLoader.memoryCache?.clear()
                    zipArchiveManager.handlePool.closeAll()
                    zipArchiveManager.sevenZManager.sessionManager.closeAll()
                    archiveDiskCache.trimToSize()
                    thumbnailDiskCache.trimToSize()
                    com.watchpicture.app.archive.SevenZKeyCache.clear()
                }
                // 后台各档：UI_HIDDEN(20)/BACKGROUND(40)/MODERATE(60) —— 压缩 Coil 缓存并关闭空闲句柄/7z 缓冲
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                    // UI moved to background: halve Coil memory cache and close idle handles/7z buffers to survive LMK
                    val memCache = imageLoader.memoryCache
                    if (memCache != null) {
                        memCache.trimToSize(memCache.maxSize / 2)
                    }
                    zipArchiveManager.handlePool.closeAll()
                    zipArchiveManager.sevenZManager.sessionManager.closeAll()
                    // 退到后台且无在途读取时，顺带按容量预算裁剪磁盘缓存
                    scheduleAutoCacheClean()
                }
                // 前台临界：RUNNING_CRITICAL(15) —— 清空全部内存缓存、句柄、7z 会话并裁剪磁盘缓存
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                    imageLoader.memoryCache?.clear()
                    zipArchiveManager.handlePool.closeAll()
                    zipArchiveManager.sevenZManager.sessionManager.closeAll()
                    archiveDiskCache.trimToSize()
                    thumbnailDiskCache.trimToSize()
                    com.watchpicture.app.archive.SevenZKeyCache.clear()
                }
                // 前台低内存：RUNNING_LOW(10) —— 压缩 Coil 缓存并回收空闲 7z 会话
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                    val memCache = imageLoader.memoryCache
                    if (memCache != null) {
                        memCache.trimToSize(memCache.maxSize / 2)
                    }
                    zipArchiveManager.sevenZManager.sessionManager.reapIdleSessions()
                }
            }
        } catch (_: Throwable) {}
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            SingletonImageLoader.get(this).memoryCache?.clear()
            zipArchiveManager.handlePool.closeAll()
            zipArchiveManager.sevenZManager.sessionManager.closeAll()
            com.watchpicture.app.archive.SevenZKeyCache.clear()
        } catch (_: Throwable) {}
    }

    companion object {
        lateinit var instance: WatchPictureApp
            private set
    }
}
