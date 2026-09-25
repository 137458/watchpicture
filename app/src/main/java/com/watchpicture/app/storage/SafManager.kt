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
        if (directDir != null && directDir.isDirectory && directDir.canRead()) {
            scanFromDirectFile(directDir)
        } else {
            scanFromDocumentFile(context, treeUri)
        }
    }

    /**
     * Scans pack items using high-speed concurrent java.io.File system access.
     * 每个子项独立处理，单个子项的失败不会中断整批扫描。
     */
    private suspend fun scanFromDirectFile(rootDir: File): List<PackItem> {
        val children = rootDir.listFiles() ?: return emptyList()
        return children.asIterable().mapIsolated("SafScan") { processDirectChild(it) }
            .sortedWith { a, b -> naturalOrderComparator.compare(a.name, b.name) }
    }

    private fun processDirectChild(child: File): PackItem? {
        if (child.name.startsWith(".") || child.name.equals("__MACOSX", ignoreCase = true)) {
            return null
        }

        if (child.isDirectory) {
            val imageFiles = com.watchpicture.app.archive.DeepFolderImageResolver.collectImages(child)
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
            val isZip = lowerName.endsWith(".zip")
            val isCbz = lowerName.endsWith(".cbz")
            val is7z = lowerName.endsWith(".7z") || lowerName.endsWith(".cb7")

            if (isZip || isCbz || is7z) {
                val cachedPassword = passwordStore.get(child.absolutePath)
                val entries = zipArchiveManager.getImageEntries(child, cachedPassword)
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
                    isCbz = isCbz,
                    is7z = is7z
                )
            }
        }
        return null
    }

    /**
     * Fallback scanning using standard Android DocumentFile for restricted SAF providers.
     */
    private fun scanFromDocumentFile(context: Context, treeUri: Uri): List<PackItem> {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val files = rootDoc.listFiles()
        val packs = mutableListOf<PackItem>()

        for (doc in files) {
            val name = doc.name ?: continue
            if (name.startsWith(".") || name.equals("__MACOSX", ignoreCase = true)) {
                continue
            }

            if (doc.isDirectory) {
                val subFiles = doc.listFiles()
                val images = subFiles.filter { it.isFile && ZipArchiveManager.isImageFile(it.name ?: "") }
                    .sortedWith { a, b -> naturalOrderComparator.compare(a.name ?: "", b.name ?: "") }

                if (images.isNotEmpty()) {
                    val first = images.first()
                    val cover = PackImage(
                        packId = doc.uri.toString(),
                        entryPath = first.name ?: "",
                        displayName = first.name ?: "",
                        isEncrypted = false,
                        fileUri = first.uri.toString()
                    )
                    packs.add(
                        DirectoryPack(
                            id = doc.uri.toString(),
                            name = name,
                            uriString = doc.uri.toString(),
                            directPath = null,
                            itemCount = images.size,
                            fileSize = doc.length(),
                            lastModified = doc.lastModified(),
                            coverImage = cover
                        )
                    )
                }
            } else if (doc.isFile) {
                val lowerName = name.lowercase()
                if (com.watchpicture.app.archive.ArchiveFileResolver.isDisallowedExtension(lowerName)) {
                    continue
                }
                val isZip = lowerName.endsWith(".zip")
                val isCbz = lowerName.endsWith(".cbz")
                val is7z = lowerName.endsWith(".7z") || lowerName.endsWith(".cb7")

                if (isZip || isCbz || is7z) {
                    // Try to resolve direct file if possible
                    val directFile = resolveDirectFile(doc.uri)
                    if (directFile != null && directFile.exists()) {
                        val cachedPassword = passwordStore.get(doc.uri.toString())
                        val entries = zipArchiveManager.getImageEntries(directFile, cachedPassword)
                        val isEncrypted = if (entries.isNotEmpty()) entries.any { it.isEncrypted } else zipArchiveManager.isEncrypted(directFile)

                        val cover = entries.firstOrNull()?.let { entry ->
                            PackImage(
                                packId = doc.uri.toString(),
                                entryPath = entry.name,
                                displayName = entry.name.substringAfterLast('/'),
                                isEncrypted = entry.isEncrypted,
                                directFilePath = directFile.absolutePath,
                                fileUri = doc.uri.toString()
                            )
                        }

                        packs.add(
                            ZipPack(
                                id = doc.uri.toString(),
                                name = name.substringBeforeLast('.'),
                                uriString = doc.uri.toString(),
                                directPath = directFile.absolutePath,
                                itemCount = entries.size,
                                isEncrypted = isEncrypted,
                                fileSize = doc.length(),
                                lastModified = doc.lastModified(),
                                coverImage = cover,
                                isCbz = isCbz,
                                is7z = is7z
                            )
                        )
                    }
                }
            }
        }

        return packs.sortedWith { a, b -> naturalOrderComparator.compare(a.name, b.name) }
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
 */
internal suspend fun <T, R : Any> Iterable<T>.mapIsolated(
    tag: String,
    process: (T) -> R?
): List<R> = coroutineScope {
    map { item ->
        async(Dispatchers.IO) {
            runCatching { process(item) }
                .onFailure { com.watchpicture.app.util.AppLog.e(tag, "子项处理失败，已跳过: $item", it) }
                .getOrNull()
        }
    }
        .awaitAll()
        .filterNotNull()
}
