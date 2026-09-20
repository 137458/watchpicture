package com.watchpicture.app.archive

import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

/**
 * Archive manager for 7z files powered by Apache Commons Compress.
 * Supports standard 7z archives as well as AES-256 encrypted archives
 * (both content encryption and header/metadata encryption).
 */
class SevenZArchiveManager {

    companion object {
        private val naturalOrderComparator = NaturalOrderComparator()
        private const val SEVEN_Z_SIGNATURE_LEN = 6

        /**
         * Quickly tests if a file starts with valid 7z magic bytes (0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C).
         */
        fun isValidSevenZArchive(file: File): Boolean {
            if (!file.exists() || file.length() < SEVEN_Z_SIGNATURE_LEN) return false
            return try {
                FileInputStream(file).use { stream ->
                    val magic = ByteArray(SEVEN_Z_SIGNATURE_LEN)
                    if (stream.read(magic) != SEVEN_Z_SIGNATURE_LEN) return false
                    SevenZFile.matches(magic, SEVEN_Z_SIGNATURE_LEN)
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Quickly tests if this file is a 7z archive.
     */
    fun isValidSevenZArchive(file: File): Boolean = Companion.isValidSevenZArchive(file)

    /**
     * Determines whether the 7z archive is encrypted (either header encryption or entry content encryption).
     */
    fun isEncrypted(file: File): Boolean {
        if (!isValidSevenZArchive(file)) return false
        return try {
            SevenZFile.builder().setFile(file).get().use { sevenZ ->
                sevenZ.entries.any { entry ->
                    isEntryEncrypted(entry)
                }
            }
        } catch (_: PasswordRequiredException) {
            true // Encrypted header requires a password to even read metadata
        } catch (e: Exception) {
            val msg = e.message?.lowercase(Locale.ROOT) ?: ""
            msg.contains("password") || msg.contains("encrypted")
        }
    }

    private fun isEntryEncrypted(entry: SevenZArchiveEntry): Boolean {
        return entry.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true
    }

    /**
     * Retrieves all valid image entries sorted in human-intuitive natural order.
     */
    fun getImageEntries(file: File, password: String? = null): List<ArchiveEntryInfo> {
        if (!isValidSevenZArchive(file)) return emptyList()

        if (password.isNullOrEmpty() && Native7z.isAvailable) {
            val session = Native7zArchiveSession.open(file.absolutePath)
            if (session != null) {
                return session.use { s ->
                    s.entries
                        .asSequence()
                        .filter { !it.isDirectory }
                        .filter { !ZipArchiveManager.isIgnoredFile(it.path) }
                        .filter { ZipArchiveManager.isImageFile(it.path) }
                        .map { entry ->
                            ArchiveEntryInfo(
                                name = entry.path,
                                uncompressedSize = entry.size,
                                isEncrypted = false
                            )
                        }
                        .sortedWith { a, b -> naturalOrderComparator.compare(a.name, b.name) }
                        .toList()
                }
            }
        }

        val builder = SevenZFile.builder().setFile(file)
        if (!password.isNullOrEmpty()) {
            builder.setPassword(password)
        }

        return try {
            builder.get().use { sevenZ ->
                sevenZ.entries
                    .asSequence()
                    .filter { !it.isDirectory }
                    .filter { !ZipArchiveManager.isIgnoredFile(it.name) }
                    .filter { ZipArchiveManager.isImageFile(it.name) }
                    .map { entry ->
                        ArchiveEntryInfo(
                            name = entry.name,
                            uncompressedSize = entry.size,
                            isEncrypted = isEntryEncrypted(entry)
                        )
                    }
                    .sortedWith { a, b -> naturalOrderComparator.compare(a.name, b.name) }
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Tests whether the provided password can successfully decrypt entries in the archive.
     */
    fun verifyPassword(file: File, password: String): Boolean {
        if (!isValidSevenZArchive(file)) return false

        return try {
            SevenZFile.builder().setFile(file).setPassword(password).get().use { sevenZ ->
                val hasEncryptedEntries = sevenZ.entries.any { isEntryEncrypted(it) }
                if (!hasEncryptedEntries) {
                    return true
                }

                val targetEntry = sevenZ.entries.firstOrNull { !it.isDirectory && it.hasStream() && isEntryEncrypted(it) }
                    ?: return true

                try {
                    sevenZ.getInputStream(targetEntry).use { stream ->
                        val buffer = ByteArray(64)
                        stream.read(buffer) >= 0
                    }
                    true
                } catch (_: OutOfMemoryError) {
                    System.gc()
                    true
                }
            }
        } catch (_: OutOfMemoryError) {
            System.gc()
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Streams the uncompressed content of a single entry on demand without writing to disk.
     * The underlying SevenZFile is tied to the lifecycle of the returned InputStream and
     * closes when the stream is closed.
     */
    fun getEntryInputStream(file: File, entryName: String, password: String? = null): InputStream {
        if (password.isNullOrEmpty() && Native7z.isAvailable) {
            val session = Native7zArchiveSession.open(file.absolutePath)
            if (session != null) {
                val entry = session.findEntry(entryName)
                if (entry != null) {
                    val bytes = session.extractToBytes(entry.index)
                    session.close()
                    if (bytes != null) {
                        return java.io.ByteArrayInputStream(bytes)
                    }
                } else {
                    session.close()
                }
            }
        }

        val normalized = entryName.replace('\\', '/')
        val nfcNormalized = java.text.Normalizer.normalize(normalized, java.text.Normalizer.Form.NFC)
        val builder = SevenZFile.builder().setFile(file)
        if (!password.isNullOrEmpty()) {
            builder.setPassword(password)
        }

        val sevenZ = builder.get()
        return try {
            val entry = sevenZ.entries.firstOrNull {
                val candidate = it.name.replace('\\', '/')
                it.name == entryName ||
                        it.name == normalized ||
                        candidate == normalized ||
                        java.text.Normalizer.normalize(candidate, java.text.Normalizer.Form.NFC) == nfcNormalized
            } ?: throw NoSuchElementException("Entry '$entryName' not found in 7z archive ${file.name}")

            val rawStream = sevenZ.getInputStream(entry)
            object : InputStream() {
                override fun read(): Int = rawStream.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = rawStream.read(b, off, len)
                override fun skip(n: Long): Long = rawStream.skip(n)
                override fun available(): Int = rawStream.available()
                override fun close() {
                    try {
                        rawStream.close()
                    } finally {
                        sevenZ.close()
                    }
                }
            }
        } catch (e: Throwable) {
            sevenZ.close()
            throw e
        }
    }

    /**
     * Extracts multiple entries in a single sequential pass through the 7z archive stream.
     * This turns an O(N^2) solid compression decompression disaster into an optimal O(N) single-pass stream,
     * drastically reducing CPU power, heating, and latency when paging or prefetching pictures.
     */
    fun extractSequentialEntries(
        file: File,
        targetEntryNames: Collection<String>,
        password: String? = null,
        onEntryExtracted: (name: String, stream: InputStream) -> Unit
    ) {
        if (!isValidSevenZArchive(file) || targetEntryNames.isEmpty()) return

        if (password.isNullOrEmpty() && Native7z.isAvailable) {
            val session = Native7zArchiveSession.open(file.absolutePath)
            if (session != null) {
                session.use { s ->
                    val targets = targetEntryNames.map { it.replace('\\', '/').trimStart('/') }.toSet()
                    val targetEntries = s.entries.filter { !it.isDirectory && it.path.trimStart('/') in targets }
                    for (entry in targetEntries) {
                        val bytes = s.extractToBytes(entry.index) ?: continue
                        java.io.ByteArrayInputStream(bytes).use { stream ->
                            onEntryExtracted(entry.path, stream)
                        }
                    }
                }
                return
            }
        }

        val targets = targetEntryNames.map { it.replace('\\', '/') }.toSet()
        val builder = SevenZFile.builder().setFile(file)
        if (!password.isNullOrEmpty()) {
            builder.setPassword(password)
        }

        builder.get().use { sevenZ ->
            val remainingTargets = targets.toMutableSet()
            while (remainingTargets.isNotEmpty()) {
                val entry = sevenZ.nextEntry ?: break
                if (entry.isDirectory) continue

                val normalizedName = entry.name.replace('\\', '/')
                if (normalizedName in remainingTargets) {
                    remainingTargets.remove(normalizedName)
                    val stream = sevenZ.getInputStream(entry)
                    onEntryExtracted(entry.name, stream)
                }
            }
        }
    }
}
