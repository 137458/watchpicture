package com.watchpicture.app.export

import android.content.Context
import android.net.Uri
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.archive.ZipArchiveManager
import com.watchpicture.app.model.PackImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 一键导出的结果。
 */
sealed interface ExportOutcome {
    data class Success(val bytes: Long) : ExportOutcome
    data class Failure(val message: String) : ExportOutcome
}

/**
 * 单条目导出：把「当前正在查看的图片 / 视频」写到用户自选位置。
 *
 * 三条字节来源，优先级从快到慢：
 * 1. 文件夹图包 → 直接复制真实文件（零解码）；
 * 2. 压缩包条目 → 经 [ZipArchiveManager.getEntryInputStream] 流式读取（内存级，不整包落盘）；
 * 3. SAF 单文件 URI → 直接转发给系统输入流。
 */
object MediaExportManager {

    private const val COPY_BUFFER_SIZE = 128 * 1024

    /** 非法文件名字符（含路径分隔符与控制字符），连续出现合并为一个下划线。 */
    private val ILLEGAL_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+")

    @Volatile
    private var fallbackArchiveManager: ZipArchiveManager? = null

    /**
     * 归档管理器：优先取应用单例，JVM 单测下 [WatchPictureApp.instance] 未初始化时退化为本地实例。
     */
    internal fun archiveManager(): ZipArchiveManager {
        runCatching { WatchPictureApp.instance }.getOrNull()?.let { return it.zipArchiveManager }
        return fallbackArchiveManager ?: synchronized(this) {
            fallbackArchiveManager ?: ZipArchiveManager().also { fallbackArchiveManager = it }
        }
    }

    /**
     * 条目所属的归档文件（zip / cbz / 7z / cb7 等）；文件夹图包返回 null。
     */
    fun archiveFileOf(item: PackImage): File? {
        val base = item.directFilePath?.let { File(it) } ?: return null
        if (!base.isFile) return null
        return base.takeIf { !ZipArchiveManager.isMediaFile(it.name) }
    }

    /**
     * 文件夹图包中条目对应的真实文件；非文件夹图包返回 null。
     */
    internal fun directMediaFile(item: PackImage): File? {
        val base = item.directFilePath?.let { File(it) } ?: return null
        if (base.isDirectory) {
            return File(base, item.entryPath.trimStart('/')).takeIf { it.isFile && it.length() > 0L }
        }
        // 部分文件夹图包把 directFilePath 直接指向媒体文件本身
        return base.takeIf { it.isFile && it.length() > 0L && ZipArchiveManager.isMediaFile(it.name) }
    }

    /**
     * 建议的导出文件名：取最后一段路径并清洗非法字符，保留原始扩展名。
     */
    fun suggestedFileName(entryPath: String): String {
        val leaf = entryPath.substringAfterLast('/')
        val cleaned = ILLEGAL_NAME_CHARS.replace(leaf, "_").trim()
        return cleaned.ifEmpty { "export" }
    }

    /**
     * 导出到真实文件系统路径。
     */
    fun exportToFile(item: PackImage, sessionPassword: String?, destination: File): ExportOutcome {
        directMediaFile(item)?.let { source ->
            return copyFile(source, destination)
        }

        val archive = archiveFileOf(item)
        if (archive != null) {
            val password = resolvePassword(item, sessionPassword, archive)
            return try {
                archiveManager().getEntryInputStream(archive, item.entryPath, password).use { input ->
                    writeStream(input, destination)
                }
            } catch (t: Throwable) {
                destination.delete()
                ExportOutcome.Failure(t.message ?: "导出失败")
            }
        }

        if (item.fileUri != null) {
            return ExportOutcome.Failure("该条目需要通过系统文件选择器导出")
        }
        return ExportOutcome.Failure("找不到条目来源")
    }

    /**
     * 导出到 SAF 目标（用户通过系统「另存为」选择的位置）。
     */
    suspend fun exportToUri(
        context: Context,
        item: PackImage,
        sessionPassword: String?,
        target: Uri
    ): ExportOutcome = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val bytes = resolver.openOutputStream(target, "wt")?.use { output ->
                val source: InputStream? = directMediaFile(item)?.let { FileInputStream(it) }
                    ?: archiveFileOf(item)?.let { archive ->
                        archiveManager().getEntryInputStream(
                            archive,
                            item.entryPath,
                            resolvePassword(item, sessionPassword, archive)
                        )
                    }
                    ?: item.fileUri?.let { raw -> resolver.openInputStream(Uri.parse(raw)) }
                source?.use { input -> input.copyTo(output, COPY_BUFFER_SIZE) } ?: 0L
            } ?: 0L

            if (bytes > 0L) ExportOutcome.Success(bytes) else ExportOutcome.Failure("条目内容为空")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            ExportOutcome.Failure(t.message ?: "导出失败")
        }
    }

    /**
     * 密码解析顺序：会话密码 → 密码本（按 packId）→ 密码本（按归档路径）。
     */
    internal fun resolvePassword(item: PackImage, sessionPassword: String?, archive: File): String? {
        sessionPassword?.takeIf { it.isNotEmpty() }?.let { return it }
        val store = runCatching { WatchPictureApp.instance.sessionPasswordStore }.getOrNull() ?: return null
        return store.get(item.packId) ?: store.get(archive.absolutePath)
    }

    private fun copyFile(source: File, destination: File): ExportOutcome = try {
        FileInputStream(source).use { input -> writeStream(input, destination) }
    } catch (t: Throwable) {
        destination.delete()
        ExportOutcome.Failure(t.message ?: "导出失败")
    }

    private fun writeStream(input: InputStream, destination: File): ExportOutcome {
        destination.parentFile?.mkdirs()
        var bytes = 0L
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                bytes += read
            }
            output.flush()
        }
        if (bytes > 0L) return ExportOutcome.Success(bytes)
        destination.delete()
        return ExportOutcome.Failure("条目内容为空")
    }
}
