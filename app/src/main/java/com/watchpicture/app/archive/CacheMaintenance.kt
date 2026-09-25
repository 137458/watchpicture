package com.watchpicture.app.archive

import android.content.Context
import com.watchpicture.app.storage.PreferencesRepository
import com.watchpicture.app.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 统一承担磁盘缓存的统计、清理与自动裁剪，避免 Application 直接混入缓存维护逻辑。
 *
 * 覆盖四类落盘数据：归档条目缓存、缩略图缓存、SAF 打开的整包落盘副本、视频播放临时副本。
 */
class CacheMaintenance(
    private val context: Context,
    private val archiveDiskCache: ArchiveDiskCache,
    private val thumbnailDiskCache: ThumbnailDiskCache,
    private val archiveFileResolver: ArchiveFileResolver,
    private val preferences: PreferencesRepository,
    private val scope: CoroutineScope
) {

    companion object {
        /** 视频播放临时副本的容量预算。 */
        const val PLAYBACK_MAX_BYTES = 256L * 1024 * 1024
    }

    /** 统计全部磁盘缓存占用。 */
    suspend fun totalSizeBytes(): Long = withContext(Dispatchers.IO) {
        archiveDiskCache.sizeOnDisk() +
            thumbnailDiskCache.sizeOnDisk() +
            archiveFileResolver.archiveCacheSizeBytes(context) +
            cacheDirectorySize(VideoLauncher.playbackDir(context))
    }

    /**
     * 清空全部磁盘缓存，返回实际释放的字节数。
     * SAF 归档落盘副本与视频播放副本被清掉后，相关图包/视频在下次打开时会按需重新落盘。
     */
    suspend fun clearAll(): Long = withContext(Dispatchers.IO) {
        val before = totalSizeBytes()
        archiveDiskCache.clearAll()
        thumbnailDiskCache.clearAll()
        archiveFileResolver.clearArchiveCache(context)
        deleteFilesIn(VideoLauncher.playbackDir(context))
        (before - totalSizeBytes()).coerceAtLeast(0L)
    }

    /**
     * 按容量预算裁剪磁盘缓存。
     *
     * 只应在应用启动时调用（此时尚无任何归档被打开），以免删除正在被读取的文件；
     * 当前台正在浏览图包时不得触发。
     */
    fun scheduleAutoTrim() {
        scope.launch {
            try {
                if (!preferences.autoCleanCacheFlow.first()) return@launch
                archiveDiskCache.trimToSize()
                thumbnailDiskCache.trimToSize()
                archiveFileResolver.trimArchiveCache(context)
                trimPlaybackCache()
            } catch (e: CancellationException) {
                // 协程取消不是清理失败，必须继续向上传播
                throw e
            } catch (e: Throwable) {
                AppLog.e("CacheClean", "自动清理缓存失败", e)
            }
        }
    }

    private fun trimPlaybackCache(): Long {
        val files = VideoLauncher.playbackDir(context).listFiles()?.filter { it.isFile } ?: return 0L
        val entries = files.map { LruCacheEntry(it.absolutePath, it.length(), it.lastModified()) }
        var freed = 0L
        for (path in planLruTrim(entries, PLAYBACK_MAX_BYTES)) {
            val file = File(path)
            val size = file.length()
            if (file.delete()) freed += size
        }
        return freed
    }

    /** 清空目录内全部文件（含写入中的 .tmp 半成品），返回释放的字节数。 */
    private fun deleteFilesIn(directory: File): Long {
        var freed = 0L
        directory.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val size = file.length()
            if (file.delete()) freed += size
        }
        return freed
    }
}
