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
     * Scans pack items using high-speed direct java.io.File system access.
     */
    private fun scanFromDirectFile(rootDir: File): List<PackItem> {
        val children = rootDir.listFiles() ?: return emptyList()
        val packs = mutableListOf<PackItem>()

        for (child in children) {
            if (child.name.startsWith(".") || child.name.equals("__MACOSX", ignoreCase = true)) {
                continue
            }

            if (child.isDirectory) {
                // Check if directory contains images
                val imageFiles = child.listFiles { file ->
                    !file.isDirectory && ZipArchiveManager.isImageFile(file.name)
                }?.sortedWith { a, b -> naturalOrderComparator.compare(a.name, b.name) } ?: emptyList()

                if (imageFiles.isNotEmpty()) {
                    val first = imageFiles.first()
                    val cover = PackImage(
                        packId = child.absolutePath,
                        entryPath = first.name,
                        displayName = first.name,
                        isEncrypted = false,
                        directFilePath = first.absolutePath
                    )
                    packs.add(
                        DirectoryPack(
                            id = child.absolutePath,
                            name = child.name,
                            uriString = Uri.fromFile(child).toString(),
                            directPath = child.absolutePath,
                            itemCount = imageFiles.size,
                            fileSize = child.length(),
                            lastModified = child.lastModified(),
                            coverImage = cover
                        )
                    )
                }
            } else if (child.isFile) {
                val lowerName = child.name.lowercase()
                val isZip = lowerName.endsWith(".zip")
                val isCbz = lowerName.endsWith(".cbz")

                if (isZip || isCbz) {
                    val isEncrypted = zipArchiveManager.isEncrypted(child)
                    val cachedPassword = passwordStore.get(child.absolutePath)
                    val entries = zipArchiveManager.getImageEntries(child, cachedPassword)

                    val cover = entries.firstOrNull()?.let { entry ->
                        PackImage(
                            packId = child.absolutePath,
                            entryPath = entry.name,
                            displayName = entry.name.substringAfterLast('/'),
                            isEncrypted = entry.isEncrypted,
                            directFilePath = child.absolutePath
                        )
                    }

                    packs.add(
                        ZipPack(
                            id = child.absolutePath,
                            name = child.nameWithoutExtension,
                            uriString = Uri.fromFile(child).toString(),
                            directPath = child.absolutePath,
                            itemCount = entries.size,
                            isEncrypted = isEncrypted,
                            fileSize = child.length(),
                            lastModified = child.lastModified(),
                            coverImage = cover,
                            isCbz = isCbz
                        )
                    )
                }
            }
        }

        return packs.sortedWith { a, b -> naturalOrderComparator.compare(a.name, b.name) }
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
                val isZip = lowerName.endsWith(".zip")
                val isCbz = lowerName.endsWith(".cbz")

                if (isZip || isCbz) {
                    // Try to resolve direct file if possible
                    val directFile = resolveDirectFile(doc.uri)
                    if (directFile != null && directFile.exists()) {
                        val isEncrypted = zipArchiveManager.isEncrypted(directFile)
                        val cachedPassword = passwordStore.get(doc.uri.toString())
                        val entries = zipArchiveManager.getImageEntries(directFile, cachedPassword)

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
                                isCbz = isCbz
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

            if (DocumentsContract.isTreeUri(uri)) {
                val treeDocId = DocumentsContract.getTreeDocumentId(uri)
                if (treeDocId.startsWith("primary:")) {
                    val relativePath = treeDocId.removePrefix("primary:")
                    return File(Environment.getExternalStorageDirectory(), relativePath)
                }
            }

            if (DocumentsContract.isDocumentUri(null, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("primary:")) {
                    val relativePath = docId.removePrefix("primary:")
                    return File(Environment.getExternalStorageDirectory(), relativePath)
                }
            }
        } catch (_: Exception) {
            // Ignore resolution errors and return null
        }
        return null
    }
}
