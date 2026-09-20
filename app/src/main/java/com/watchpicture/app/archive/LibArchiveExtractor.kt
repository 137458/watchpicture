package com.watchpicture.app.archive

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * High-performance Native C/C++ archive extractor powered by libarchive via JNI.
 *
 * Employs [Os.mmap] to map the archive directly into process virtual memory, avoiding
 * intermediate JVM heap buffers, and streams decompressed entries straight to disk.
 *
 * Supports single-pass forward stream scanning with opportunistic caching for solid archives.
 */
object LibArchiveExtractor {

    val isAvailable: Boolean by lazy {
        try {
            val handle = Archive.readNew()
            Archive.readFree(handle)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Extracts entries from an archive using libarchive native JNI.
     *
     * @param file The archive file.
     * @param targetEntryName Name of the entry that must be extracted.
     * @param password Optional archive password.
     * @param maxLookahead Number of entries to opportunistically cache after target is found.
     * @param onEntryExtracted Callback invoked when an entry is extracted; receives entry name and temp file.
     * @return True if the target entry was extracted, false otherwise.
     */
    fun extractWithOpportunisticCache(
        file: File,
        targetEntryName: String,
        password: String?,
        maxLookahead: Int,
        tempDirectory: File? = null,
        onEntryExtracted: (name: String, extractedFile: File) -> Unit
    ): Boolean {
        if (!isAvailable) return false

        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: Throwable) {
            return false
        }

        var address = 0L
        val size = pfd.statSize

        try {
            address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)

            val archive = Archive.readNew()
            try {
                Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
                if (!password.isNullOrEmpty()) {
                    Archive.readAddPassphrase(archive, password.toByteArray())
                }
                Archive.readSupportFilterAll(archive)
                Archive.readSupportFormatAll(archive)
                Archive.readOpenMemoryUnsafe(archive, address, size)

                val normalizedTarget = targetEntryName.replace('\\', '/')
                val nfcTarget = java.text.Normalizer.normalize(normalizedTarget, java.text.Normalizer.Form.NFC)

                var targetFound = false
                var lookaheadRemaining = maxLookahead
                val buffer = ByteBuffer.allocateDirect(64 * 1024)

                while (true) {
                    val entryHandle = Archive.readNextHeader(archive)
                    if (entryHandle == 0L) break

                    val isFile = ArchiveEntry.filetype(entryHandle) == ArchiveEntry.AE_IFREG
                    if (!isFile) continue

                    val entryName = ArchiveEntry.pathnameUtf8(entryHandle)
                        ?: ArchiveEntry.pathname(entryHandle)?.decodeToString()
                        ?: continue

                    val entryNormalized = entryName.replace('\\', '/')
                    val isImage = ZipArchiveManager.isImageFile(entryName)
                    val isTarget = (entryName == targetEntryName ||
                            entryNormalized == normalizedTarget ||
                            java.text.Normalizer.normalize(entryNormalized, java.text.Normalizer.Form.NFC) == nfcTarget)

                    if (isTarget || (isImage && (!targetFound || lookaheadRemaining > 0))) {
                        // Extract to a safe internal cache temp file to strictly comply with Scoped Storage
                        val safeDir = tempDirectory
                            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.cacheDir }.getOrNull()
                            ?: File(System.getProperty("java.io.tmpdir") ?: ".")
                        if (!safeDir.exists()) safeDir.mkdirs()
                        val tempFile = File.createTempFile("libarc_", ".tmp", safeDir)
                        try {
                            FileOutputStream(tempFile).use { fos ->
                                while (true) {
                                    buffer.clear()
                                    Archive.readData(archive, buffer)
                                    buffer.flip()
                                    if (!buffer.hasRemaining()) break
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    fos.write(bytes)
                                }
                                fos.flush()
                            }
                            if (tempFile.exists() && tempFile.length() > 0L) {
                                onEntryExtracted(entryName, tempFile)
                                if (isTarget) {
                                    targetFound = true
                                } else if (targetFound) {
                                    lookaheadRemaining--
                                }
                            } else {
                                tempFile.delete()
                            }
                        } catch (e: Throwable) {
                            tempFile.delete()
                            throw e
                        }
                    } else {
                        // Skip entry data rapidly in native C code without buffer copying
                        Archive.readDataSkip(archive)
                    }

                    if (targetFound && lookaheadRemaining <= 0) {
                        break
                    }
                }

                return targetFound
            } finally {
                Archive.readFree(archive)
            }
        } catch (_: Throwable) {
            return false
        } finally {
            if (address != 0L) {
                try {
                    Os.munmap(address, size)
                } catch (_: Throwable) {}
            }
            try {
                pfd.close()
            } catch (_: Throwable) {}
        }
    }
}
