package com.watchpicture.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.watchpicture.app.archive.NaturalOrderComparator
import com.watchpicture.app.archive.ZipArchiveManager
import com.watchpicture.app.model.DirectoryPack
import com.watchpicture.app.model.PackImage
import com.watchpicture.app.model.PackItem
import com.watchpicture.app.model.ZipPack
import com.watchpicture.app.security.SessionPasswordStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles SAF authorization persistence, direct file path resolution,
 * and high-performance pack scanning.
 */
class SafManager(
    private val zipArchiveManager: ZipArchiveManager,
    private val passwordStore: SessionPasswordStore
) {
    private val naturalOrderComparator = NaturalOrderComparator()

    /**
     * Persists URI read permissions across device reboots and app restarts.
     */
    fun takePersistablePermission(context: Context, treeUri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (_: SecurityException) {
            // Some document providers do not support persistable permissions
        }
    }

    /**
     * Scans picture packs under the selected directory tree.
     */
    suspend fun scanPacks(context: Context, treeUri: Uri): List<PackItem> = withContext(Dispatchers.IO) {
        val directDir = resolveDirectFile(treeUri)
        val useDirect = directDir != null && directDir.isDirectory && directDir.canRead()
        val strategy = if (useDirect) "直接文件" else "DocumentFile回退"
        val directPacks = if (useDirect) scanFromDirectFile(directDir!!) else emptyList()
        // 裸文件路径可能因目录权限（例如由其它应用以 0770 创建）而列不出任何内容，
        // 此时改走 SAF provider 读取，避免把「读不到」误报成「没有图包」。
        val packs = if (directPacks.isNotEmpty() || !useDirect) {
            directPacks
        } else {
            scanFromDocumentFile(context, treeUri)
        }
        if (packs.isEmpty()) {
            // release 只保留 ERROR 级日志；「文件管理器里看得到、应用里一个图包都没有」属于需要
            // 用户可见排查依据的异常状态，这里把策略与树 documentId 一并留痕。
            com.watchpicture.app.util.AppLog.e(
                "SafScan",
                "所选目录未解析出任何图包: 策略=$strategy treeId=${documentIdOf(treeUri) ?: "未知"} " +
                    "directDir=${directDir?.absolutePath ?: "无法解析"}"
            )
        }
        packs
    }

    /**
     * Scans pack items using high-speed concurrent java.io.File system access.
     * 每个子项独立处理，单个子项的失败不会中断整批扫描。
     */
    private suspend fun scanFromDirectFile(rootDir: File): List<PackItem> {
        val children = rootDir.listFiles() ?: return emptyList()
        val packs = children.asIterable()
            .mapIsolated(tag = "SafScan", describe = { it.name }) { processDirectChild(it) }
            .sortedWith { a, b -> naturalOrderComparator.compare(a.name, b.name) }
        if (packs.isEmpty() && children.isNotEmpty()) {
            com.watchpicture.app.util.AppLog.e(
                "SafScan",
                "直接文件扫描结果为空: 子项=${children.size} ${describeChildren(children)}"
            )
        }
        return packs
    }

    /**
     * 仅用于空结果诊断：统计子项构成，判断是「没有可识别的图包」还是「归档被过滤掉了」。
     */
    private fun describeChildren(children: Array<File>): String {
        var dirs = 0
        var archives = 0
        var others = 0
        for (child in children) {
            when {
                child.isDirectory -> dirs++
                isArchiveFile(child.name) -> archives++
                else -> others++
            }
        }
        return "目录=$dirs 压缩包=$archives 其它=$others"
    }

    private fun isArchiveFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".zip") || lower.endsWith(".cbz") ||
            lower.endsWith(".7z") || lower.endsWith(".cb7")
    }

    private fun documentIdOf(uri: Uri): String? = try {
        DocumentsContract.getDocumentId(uri)
    } catch (_: Exception) {
        null
    }

    private fun processDirectChild(child: File): PackItem? {
        if (child.name.startsWith(".") || child.name.equals("__MACOSX", ignoreCase = true)) {
            return null
        }

        if (child.isDirectory) {
            val imageFiles = com.watchpicture.app.archive.DeepFolderImageResolver.collectMedia(child)
            if (imageFiles.isNotEmpty()) {
                val first = imageFiles.first()
                val relPath = first.relativeTo(child).path.replace('\\', '/')
                val cover = PackImage(
                    packId = child.absolutePath,
                    entryPath = relPath,
                    displayName = first.name,
                    isEncrypted = false,
                    directFilePath = first.absolutePath
                )
                val totalSize = imageFiles.sumOf { it.length() }.coerceAtLeast(child.length())
                return DirectoryPack(
                    id = child.absolutePath,
                    name = child.name,
                    uriString = Uri.fromFile(child).toString(),
                    directPath = child.absolutePath,
                    itemCount = imageFiles.size,
                    fileSize = totalSize,
                    lastModified = child.lastModified(),
                    coverImage = cover
                )
            }
        } else if (child.isFile) {
            val lowerName = child.name.lowercase()
            if (com.watchpicture.app.archive.ArchiveFileResolver.isDisallowedExtension(lowerName)) {
                return null
            }

            if (isArchiveFile(lowerName)) {
                val cachedPassword = passwordStore.get(child.absolutePath)
                val entries = zipArchiveManager.getMediaEntries(child, cachedPassword)
                val isEncrypted = if (entries.isNotEmpty()) entries.any { it.isEncrypted } else zipArchiveManager.isEncrypted(child)

                val cover = entries.firstOrNull()?.let { entry ->
                    PackImage(
                        packId = child.absolutePath,
                        entryPath = entry.name,
                        displayName = entry.name.substringAfterLast('/'),
                        isEncrypted = entry.isEncrypted,
                        directFilePath = child.absolutePath
                    )
                }

                return ZipPack(
                    id = child.absolutePath,
                    name = child.nameWithoutExtension,
                    uriString = Uri.fromFile(child).toString(),
                    directPath = child.absolutePath,
                    itemCount = entries.size,
                    isEncrypted = isEncrypted,
                    fileSize = child.length(),
                    lastModified = child.lastModified(),
                    coverImage = cover,
                    isCbz = lowerName.endsWith(".cbz"),
                    is7z = lowerName.endsWith(".7z") || lowerName.endsWith(".cb7")
                )
            }
        }
        return null
    }

    /**
     * Fallback scanning using standard Android DocumentFile for restricted SAF providers.
     * 与直接文件系统路径一致，每个子项独立处理，单个子项失败不会清空整张图包列表。
     */
    private fun scanFromDocumentFile(context: Context, treeUri: Uri): List<PackItem> {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return rootDoc.listFiles()
            .mapNotNull { doc ->
                runCatching { processDocumentChild(doc) }
                    .onFailure { cause ->
                        com.watchpicture.app.util.AppLog.e(
                            "SafScan",
                            "文档子项处理失败，已跳过: ${doc.name ?: doc.uri}",
                            cause
                        )
                    }
                    .getOrNull()
            }
            .sortedWith { a, b -> naturalOrderComparator.compare(a.name, b.name) }
    }

    private fun processDocumentChild(doc: DocumentFile): PackItem? {
        val name = doc.name ?: return null
        if (name.startsWith(".") || name.equals("__MACOSX", ignoreCase = true)) return null

        if (doc.isDirectory) {
            val media = doc.listFiles()
                .filter { it.isFile && ZipArchiveManager.isMediaFile(it.name ?: "") }
                .sortedWith { a, b -> naturalOrderComparator.compare(a.name ?: "", b.name ?: "") }

            if (media.isEmpty()) return null
            val first = media.first()
            return DirectoryPack(
                id = doc.uri.toString(),
                name = name,
                uriString = doc.uri.toString(),
                directPath = null,
                itemCount = media.size,
                fileSize = doc.length(),
                lastModified = doc.lastModified(),
                coverImage = PackImage(
                    packId = doc.uri.toString(),
                    entryPath = first.name ?: "",
                    displayName = first.name ?: "",
                    isEncrypted = false,
                    fileUri = first.uri.toString()
                )
            )
        }

        if (!doc.isFile) return null
        val lowerName = name.lowercase()
        if (com.watchpicture.app.archive.ArchiveFileResolver.isDisallowedExtension(lowerName)) return null
        if (!isArchiveFile(lowerName)) return null

        // provider-only 归档（裸 File 读不到，例如目录由其它应用以 0770 创建）不能在此丢弃：
        // 保留条目与原始 Uri，首次打开时经 ContentResolver 按需落盘后即可浏览。
        val directFile = resolveDirectFile(doc.uri)?.takeIf { it.exists() }
        if (directFile == null) {
            com.watchpicture.app.util.AppLog.e(
                "SafScan",
                "归档需经 provider 读取，将在打开时按需落盘: $name (documentId=${documentIdOf(doc.uri) ?: "未知"})"
            )
            return ZipPack(
                id = doc.uri.toString(),
                name = name.substringBeforeLast('.'),
                uriString = doc.uri.toString(),
                directPath = null,
                itemCount = 0,
                isEncrypted = false,
                fileSize = doc.length(),
                lastModified = doc.lastModified(),
                coverImage = null,
                isCbz = lowerName.endsWith(".cbz"),
                is7z = lowerName.endsWith(".7z") || lowerName.endsWith(".cb7")
            )
        }
        val isCbz = lowerName.endsWith(".cbz")
        val cachedPassword = passwordStore.get(doc.uri.toString())
        val entries = zipArchiveManager.getMediaEntries(directFile, cachedPassword)
        val isEncrypted = if (entries.isNotEmpty()) {
            entries.any { it.isEncrypted }
        } else {
            zipArchiveManager.isEncrypted(directFile)
        }

        return ZipPack(
            id = doc.uri.toString(),
            name = name.substringBeforeLast('.'),
            uriString = doc.uri.toString(),
            directPath = directFile.absolutePath,
            itemCount = entries.size,
            isEncrypted = isEncrypted,
            fileSize = doc.length(),
            lastModified = doc.lastModified(),
            coverImage = entries.firstOrNull()?.let { entry ->
                PackImage(
                    packId = doc.uri.toString(),
                    entryPath = entry.name,
                    displayName = entry.name.substringAfterLast('/'),
                    isEncrypted = entry.isEncrypted,
                    directFilePath = directFile.absolutePath,
                    fileUri = doc.uri.toString()
                )
            },
            isCbz = isCbz,
            is7z = lowerName.endsWith(".7z") || lowerName.endsWith(".cb7")
        )
    }

    /**
     * Resolves SAF Tree or Document URI to direct filesystem File if located on primary storage.
     */
    fun resolveDirectFile(uri: Uri): File? {
        try {
            if (uri.scheme == "file") {
                return uri.path?.let { File(it) }
            }

            // Attempt to resolve document ID first (supports tree-child document URIs and standalone document URIs)
            val docId = try {
                DocumentsContract.getDocumentId(uri)
            } catch (_: Exception) {
                if (DocumentsContract.isTreeUri(uri)) {
                    try {
                        DocumentsContract.getTreeDocumentId(uri)
                    } catch (_: Exception) {
                        null
                    }
                } else null
            }

            if (docId != null) {
                if (docId.startsWith("primary:")) {
                    val relativePath = docId.removePrefix("primary:")
                    return safeResolve(Environment.getExternalStorageDirectory(), relativePath)
                } else if (docId.contains(':')) {
                    val parts = docId.split(':', limit = 2)
                    val volumeId = parts[0]
                    if (isValidVolumeId(volumeId)) {
                        val relativePath = parts.getOrNull(1) ?: ""
                        val storageRoot = File("/storage", volumeId)
                        safeResolve(storageRoot, relativePath)?.let { if (it.exists()) return it }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore resolution errors and return null
        }
        return null
    }

    /**
     * Resolves [relative] against [root] while guaranteeing the result stays inside [root].
     * Rejects traversal segments (".."), absolute-separator injection, and any result that
     * escapes [root] after normalization. Returns null on offense.
     */
    private fun safeResolve(root: File, relative: String): File? {
        try {
            // Reject any path that could escape the tree via parent traversal or
            // separator injection before it reaches the filesystem.
            if (relative.contains("..") || relative.contains("\\")) {
                return null
            }
            val resolved = File(root, relative)
            val rootCanonical = root.canonicalPath
            val targetCanonical = resolved.canonicalPath
            if (targetCanonical != rootCanonical &&
                !targetCanonical.startsWith(rootCanonical + File.separator)
            ) {
                return null
            }
            return resolved
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Volume id must be a plain volume name (letters/digits/underscore/hyphen/dot) so that
     * "../../" style injection cannot select an arbitrary /storage path.
     */
    private fun isValidVolumeId(volumeId: String): Boolean {
        return volumeId.isNotEmpty() && volumeId.matches(Regex("[A-Za-z0-9_.-]+"))
    }
}

/**
 * 并发处理 [this] 中的每一个子项，并对单个子项的失败做隔离。
 *
 * 失败项只记录日志并被丢弃，不会中断整批处理：扫描图包时任何一个异常子项（损坏归档、
 * 不可读目录等）此前都会让 `awaitAll` 抛出，进而清空整张图包列表并误报“没有找到压缩包”。
 *
 * @param describe 生成用于日志的子项简述。刻意不直接打印子项本身，避免把用户文件的
 *   完整路径写进 release 日志。
 */
internal suspend fun <T, R : Any> Iterable<T>.mapIsolated(
    tag: String,
    describe: (T) -> String,
    process: (T) -> R?
): List<R> = coroutineScope {
    map { item ->
        async(Dispatchers.IO) {
            runCatching { process(item) }
                .onFailure { cause ->
                    // 协程取消不是业务失败，必须继续向上传播，否则会吞掉取消信号
                    if (cause is CancellationException) throw cause
                    com.watchpicture.app.util.AppLog.e(tag, "子项处理失败，已跳过: ${describe(item)}", cause)
                }
                .getOrNull()
        }
    }
        .awaitAll()
        .filterNotNull()
}
