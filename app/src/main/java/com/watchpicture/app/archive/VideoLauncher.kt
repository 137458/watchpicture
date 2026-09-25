package com.watchpicture.app.archive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.model.PackImage
import com.watchpicture.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 视频条目播放器桥接：把图包内的视频解析为系统播放器可直接消费的 content Uri。
 *
 * - 文件夹图包：直接共享磁盘上的真实文件；
 * - SAF 单文件：直接共享原始 content Uri；
 * - 压缩包图包：按需把该条目流式落盘到 cacheDir/playback（保留扩展名）后共享。
 */
object VideoLauncher {

    internal const val PLAYBACK_DIR = "playback"
    private const val COPY_BUFFER_SIZE = 64 * 1024

    /** 调起系统播放器播放 [item]，返回是否成功发起播放。 */
    suspend fun play(context: Context, item: PackImage): Boolean {
        val playable = resolvePlayable(context, item)
        if (playable == null) {
            AppLog.e("VideoPlay", "无法解析可播放来源: ${item.displayName}", null)
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(playable.uri, playable.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, item.displayName))
            true
        } catch (e: Exception) {
            AppLog.e("VideoPlay", "无可用视频播放器: ${item.displayName}", e)
            false
        }
    }

    internal data class Playable(val uri: Uri, val mimeType: String)

    internal suspend fun resolvePlayable(context: Context, item: PackImage): Playable? =
        withContext(Dispatchers.IO) {
            val mimeType = mimeTypeOf(item.displayName)

            // 1. 文件夹图包：entryPath 直接对应磁盘真实文件
            directEntryFile(item)?.let { file ->
                return@withContext shareViaFileProvider(context, file, mimeType)
            }

            // 2. SAF 单文件条目：直接共享原始 content Uri
            item.fileUri?.let { raw ->
                return@withContext Playable(Uri.parse(raw), mimeType)
            }

            // 3. 压缩包条目：流式落盘后再共享
            materializeArchiveEntry(context, item)?.let { file ->
                return@withContext shareViaFileProvider(context, file, mimeType)
            }

            null
        }

    private fun directEntryFile(item: PackImage): File? {
        val base = item.directFilePath?.let { File(it) } ?: return null
        if (!base.isDirectory) return null
        val candidate = File(base, item.entryPath)
        return candidate.takeIf { it.exists() && it.isFile && it.length() > 0L }
    }

    private suspend fun materializeArchiveEntry(context: Context, item: PackImage): File? {
        val archive = item.directFilePath?.let { File(it) } ?: return null
        if (!archive.isFile) return null

        val target = File(playbackDir(context), playbackFileName(item))
        if (target.exists() && target.length() > 0L) return target

        val passwordStore = WatchPictureApp.instance.sessionPasswordStore
        val password = passwordStore.get(item.packId) ?: passwordStore.get(archive.absolutePath)
        val temp = File(target.absolutePath + ".tmp")
        return try {
            WatchPictureApp.instance.zipArchiveManager
                .getEntryInputStream(archive, item.entryPath, password)
                .use { input ->
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output, COPY_BUFFER_SIZE)
                    }
                }
            if (temp.length() > 0L) {
                // 落盘缓存键包含条目路径与文件名，同名旧文件先清理以刷新内容
                if (target.exists()) target.delete()
                if (temp.renameTo(target)) target else temp
            } else {
                temp.delete()
                null
            }
        } catch (e: Exception) {
            AppLog.e("VideoPlay", "视频条目落盘失败: ${item.displayName}", e)
            temp.delete()
            null
        }
    }

    /** 播放临时副本目录，同时由 [CacheMaintenance] 纳入容量预算与清理。 */
    internal fun playbackDir(context: Context): File =
        File(context.cacheDir, PLAYBACK_DIR).apply { mkdirs() }

    private fun playbackFileName(item: PackImage): String {
        val token = Integer.toHexString("${item.packId}#${item.entryPath}".hashCode())
        val safeName = item.displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return "${token}_$safeName"
    }

    private fun shareViaFileProvider(context: Context, file: File, mimeType: String): Playable? =
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            Playable(uri, mimeType)
        } catch (e: Exception) {
            AppLog.e("VideoPlay", "文件共享失败: ${file.name}", e)
            null
        }

    /**
     * 依据扩展名解析 MIME；未知扩展名统一回落视频通配类型，避免系统播放器拒绝打开。
     */
    internal fun mimeTypeOf(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/*"
    }
}
