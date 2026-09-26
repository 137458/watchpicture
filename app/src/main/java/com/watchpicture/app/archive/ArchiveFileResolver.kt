package com.watchpicture.app.archive

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.watchpicture.app.model.PackImage
import com.watchpicture.app.model.ZipPack
import com.watchpicture.app.security.SessionPasswordStore
import com.watchpicture.app.storage.SafManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Resolves single archive files (ZIP / CBZ) from either direct filesystem paths
 * or SAF / ContentProvider Uris with optimized zero-copy and fallback streaming cache.
 */
class ArchiveFileResolver(
    private val zipArchiveManager: ZipArchiveManager,
    private val passwordStore: SessionPasswordStore,
    private val safManager: SafManager = SafManager(zipArchiveManager, passwordStore)
) {

    companion object {
        private const val BUFFER_SIZE = 64 * 1024 // 64KB high-speed stream buffer

        /**
         * SAF 归档落盘缓存目录的容量预算。该目录保存的是通过 ContentProvider 打开的整包副本，
         * 单个大包可达数百 MB 且此前没有任何上限，是缓存占满存储的主因。
         */
        const val OPENED_ARCHIVES_MAX_BYTES = 512L * 1024 * 1024 // 512 MB

        /** SAF 归档落盘缓存的目录名（位于 cacheDir 下）。 */
        const val OPENED_ARCHIVES_DIR = "opened_archives"

        private val FORBIDDEN_EXTENSIONS = setOf(
            "apk", "xapk", "apks", "apkm", "aab", "jar", "aar", "dex", "ipa"
        )
        private val ALLOWED_EXTENSIONS = setOf("zip", "cbz", "7z", "cb7")

        /**
         * Checks if the filename ends with an explicitly disallowed application or library extension.
         */
        fun isDisallowedExtension(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext in FORBIDDEN_EXTENSIONS
        }

        /**
         * 裸路径是否真正可读。目录权限受限（如 0770）时 stat 可见但 canRead() 为 false，
         * zip4j 拿到这种 File 会抛「no read access for the input zip file」并以空条目收场，
         * 调用方必须放行到 provider 落盘通路而不是在此丢弃。
         */
        internal fun isDirectlyReadable(file: File?): Boolean =
            file != null && file.exists() && file.canRead()

        /**
         * 落盘副本体积校验：空副本无效；provider 声明了大小时必须严格比对——归档的表头魔数
         * 在文件开头，被截断的副本仍能通过 isValidArchive，落盘后才会在解析中央目录时失败，
         * 症状是「图包内未发现有效图片」。
         */
        internal fun isCopySizeValid(copiedSize: Long, expectedSize: Long?): Boolean =
            copiedSize > 0L && (expectedSize == null || expectedSize == copiedSize)
    }

    /**
     * Quickly verifies if a file starts with valid ZIP magic bytes:
     * - PK\x03\x04 (0x50 0x4B 0x03 0x04) standard entry
     * - PK\x05\x06 (0x50 0x4B 0x05 0x06) empty archive
     * - PK\x07\x08 (0x50 0x4B 0x07 0x08) spanned archive
     */
    fun isValidZipArchive(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            FileInputStream(file).use { stream ->
                val magic = ByteArray(4)
                if (stream.read(magic) != 4) return false
                magic[0] == 0x50.toByte() &&
                        magic[1] == 0x4B.toByte() &&
                        ((magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()) ||
                                (magic[2] == 0x05.toByte() && magic[3] == 0x06.toByte()) ||
                                (magic[2] == 0x07.toByte() && magic[3] == 0x08.toByte()))
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Quickly verifies if a file starts with valid 7z magic bytes: 37 7A BC AF 27 1C
     */
    fun isValidSevenZArchive(file: File): Boolean = SevenZArchiveManager.isValidSevenZArchive(file)

    /**
     * Checks if the file is a supported archive format (ZIP, CBZ, or 7z).
     */
    fun isValidArchive(file: File): Boolean = isValidZipArchive(file) || isValidSevenZArchive(file)

    /**
     * Detects whether a ZIP file is an Android application package (.apk)
     * or software library containing compiled code or manifests.
     */
    fun isAppOrPackageArchive(file: File): Boolean {
        if (isDisallowedExtension(file.name)) return true
        if (isValidSevenZArchive(file)) return false
        if (!isValidZipArchive(file)) return false

        return try {
            net.lingala.zip4j.ZipFile(file).use { zip ->
                zip.fileHeaders.any { header ->
                    val name = header.fileName.lowercase()
                    name == "androidmanifest.xml" ||
                            name == "classes.dex" ||
                            name.startsWith("classes") && name.endsWith(".dex") ||
                            name == "resources.arsc" ||
                            (name.startsWith("meta-inf/") && (name.endsWith(".sf") || name.endsWith(".rsa") || name.endsWith(".dsa")))
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Constructs a ZipPack model from a resolved local file.
     *
     * @param displayName 原始 Uri 上带回来的用户可见文件名。落盘副本的文件名形如 `hash_原文件名`，
     *   直接使用会把内部缓存命名暴露到图包标题里，因此优先采用该显示名。
     */
    fun createZipPackFromFile(
        file: File,
        uriString: String? = null,
        cachedPassword: String? = null,
        displayName: String? = null
    ): ZipPack? {
        if (!isValidArchive(file)) return null

        val lowerName = file.name.lowercase()
        val ext = lowerName.substringAfterLast('.', "")
        if (ext !in ALLOWED_EXTENSIONS) return null

        // Detect and reject APK or code package disguised as a ZIP
        if (isAppOrPackageArchive(file)) return null

        val isCbz = lowerName.endsWith(".cbz")
        val is7z = lowerName.endsWith(".7z") || lowerName.endsWith(".cb7") || isValidSevenZArchive(file)
        val password = cachedPassword ?: passwordStore.get(file.absolutePath)
        val entries = zipArchiveManager.getMediaEntries(file, password)
        val isEncrypted = if (entries.isNotEmpty()) entries.any { it.isEncrypted } else zipArchiveManager.isEncrypted(file)

        val fallbackUri = uriString ?: "file://${file.absolutePath}"
        val cover = entries.firstOrNull()?.let { entry ->
            PackImage(
                packId = file.absolutePath,
                entryPath = entry.name,
                displayName = entry.name.substringAfterLast('/'),
                isEncrypted = entry.isEncrypted,
                directFilePath = file.absolutePath,
                fileUri = fallbackUri
            )
        }

        val name = displayName?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }
            ?: file.nameWithoutExtension.ifEmpty { file.name }
        return ZipPack(
            id = file.absolutePath,
            name = name,
            uriString = fallbackUri,
            directPath = file.absolutePath,
            itemCount = entries.size,
            isEncrypted = isEncrypted,
            fileSize = file.length(),
            lastModified = file.lastModified(),
            coverImage = cover,
            isCbz = isCbz,
            is7z = is7z
        )
    }

    /**
     * Deletes a cached archive file from the internal opened_archives directory.
     */
    fun deleteArchiveCache(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (file.exists() && file.parentFile?.name == OPENED_ARCHIVES_DIR) {
                file.delete()
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 统计 SAF 归档落盘缓存的当前占用字节数（忽略写入中的 .tmp 半成品）。
     */
    fun archiveCacheSizeBytes(context: Context): Long = cacheDirectorySize(openedArchivesDir(context))

    /**
     * 按 LRU 把 SAF 归档落盘缓存裁剪到 [maxBytes] 预算以内，返回释放的字节数。
     * 仅在应用启动（尚无任何归档被打开）时调用，避免删除在途读取的副本。
     */
    fun trimArchiveCache(context: Context, maxBytes: Long = OPENED_ARCHIVES_MAX_BYTES): Long =
        deleteFiles(planLruTrim(openedArchivesEntries(context), maxBytes))

    /**
     * 清空 SAF 归档落盘缓存，返回释放的字节数。被清掉的条目在下次打开时会按需重新落盘。
     */
    fun clearArchiveCache(context: Context): Long =
        deleteFiles(openedArchivesEntries(context).map { it.path })

    private fun openedArchivesDir(context: Context): File =
        File(context.cacheDir, OPENED_ARCHIVES_DIR)

    private fun openedArchivesEntries(context: Context): List<LruCacheEntry> = openedArchivesDir(context)
        .listFiles()
        ?.filter { it.isFile && !it.name.endsWith(".tmp") }
        ?.map { LruCacheEntry(it.absolutePath, it.length(), it.lastModified()) }
        ?: emptyList()

    private fun deleteFiles(paths: List<String>): Long {
        var freed = 0L
        for (path in paths) {
            val file = File(path)
            val length = file.length()
            if (file.delete()) freed += length
        }
        return freed
    }

    /**
     * Resolves an arbitrary Uri (direct file, SAF document, or external ContentProvider)
     * to a local File and returns the corresponding ZipPack.
     */
    suspend fun resolve(context: Context, uri: Uri): ZipPack? = withContext(Dispatchers.IO) {
        // Step 1: Zero-copy direct resolution
        val directFile = safManager.resolveDirectFile(uri)
        if (directFile != null && isDirectlyReadable(directFile)) {
            val pack = createZipPackFromFile(directFile, uri.toString())
            if (pack != null) return@withContext pack
        }

        // Step 2: Fallback streaming cache for restricted ContentProviders
        val cachedFile = cacheFromContentProvider(context, uri) ?: return@withContext null
        val displayName = queryDisplayName(context, uri)?.let { raw ->
            runCatching { Uri.decode(raw) }.getOrDefault(raw)
        }
        createZipPackFromFile(cachedFile, uri.toString(), displayName = displayName)
    }

    /**
     * Streams content from ContentResolver into an application cache file using a 64KB buffer.
     * Reuses existing cache if the file size and content match.
     */
    private fun cacheFromContentProvider(context: Context, uri: Uri): File? {
        val resolver = context.contentResolver
        val rawName = queryDisplayName(context, uri) ?: "archive_${System.currentTimeMillis()}.zip"
        val decodedName = try { Uri.decode(rawName) } catch (_: Exception) { rawName } ?: rawName
        val safeName = decodedName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val cacheDir = File(context.cacheDir, OPENED_ARCHIVES_DIR).apply { mkdirs() }

        // Generate stable cache file name based on Uri hash and filename
        val uriHash = (uri.toString().hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        val targetFile = File(cacheDir, "${uriHash}_$safeName")

        val expectedSize = queryFileSize(context, uri)
        if (targetFile.exists() && isValidArchive(targetFile) && isCopySizeValid(targetFile.length(), expectedSize)) {
            return targetFile // Cache hit, skip redundant I/O
        }

        val tempFile = File(cacheDir, "${targetFile.name}.tmp")
        return try {
            val copied = runCatching {
                // U1: openFileDescriptor 拿 provider 侧 fd（绕开 FUSE），FileChannel.transferTo
                // 底层即 sendfile（minSdk 24 全兼容），落盘零 CPU 级拷贝。provider 返回管道 fd
                // （size()=0、sendfile 不适用）或任何失败时回退 openInputStream 流式复制。
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    java.io.FileInputStream(pfd.fileDescriptor).channel.use { source ->
                        java.io.FileOutputStream(tempFile).channel.use { target ->
                            val size = source.size()
                            if (size <= 0L) return@use false
                            var position = 0L
                            while (position < size) {
                                position += source.transferTo(position, size - position, target)
                            }
                            true
                        }
                    }
                } ?: false
            }.getOrDefault(false)

            if (!copied) {
                val inputStream: InputStream = resolver.openInputStream(uri) ?: return null
                BufferedInputStream(inputStream, BUFFER_SIZE).use { input ->
                    BufferedOutputStream(FileOutputStream(tempFile), BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                        output.flush()
                    }
                }
            }

            val copiedSize = if (tempFile.exists()) tempFile.length() else 0L
            if (isCopySizeValid(copiedSize, expectedSize) && isValidArchive(tempFile)) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                targetFile
            } else {
                com.watchpicture.app.util.AppLog.e(
                    "ArchiveMaterialize",
                    "落盘副本校验失败: ${targetFile.name.take(48)} " +
                        "期望体积=${expectedSize ?: "未知"} 实际体积=$copiedSize"
                )
                tempFile.delete()
                null
            }
        } catch (_: Exception) {
            if (tempFile.exists()) tempFile.delete()
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment
        }
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            } ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    private fun queryFileSize(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
