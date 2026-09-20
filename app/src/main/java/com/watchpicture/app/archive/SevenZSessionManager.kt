package com.watchpicture.app.archive

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Stateful session manager for active 7z archive streams.
 *
 * Eliminates O(N^2) decompression re-scans in solid 7z archives during sequential reading
 * by keeping an open forward stream session. Sequential next-page requests advance O(1)
 * directly in the active stream instead of rewinding to byte 0 and re-decompressing.
 */
class SevenZSessionManager(
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 60_000L // 60 seconds
    }

    private class Session(
        val file: File,
        val password: String?
    ) {
        val lock = Mutex()
        var sevenZ: SevenZFile? = null
        var entries: List<SevenZArchiveEntry> = emptyList()
        var currentEntryIndex: Int = -1
        var lastAccessTime: Long = System.currentTimeMillis()

        fun openIfNeeded() {
            if (sevenZ == null) {
                val builder = SevenZFile.builder().setFile(file)
                if (!password.isNullOrEmpty()) {
                    builder.setPassword(password)
                }
                val instance = builder.get()
                sevenZ = instance
                entries = instance.entries.toList()
                currentEntryIndex = -1
            }
        }

        fun reset() {
            close()
            openIfNeeded()
        }

        fun close() {
            runCatching { sevenZ?.close() }
            sevenZ = null
            entries = emptyList()
            currentEntryIndex = -1
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    val activeSessionCount: Int
        get() = sessions.size

    private fun getSessionKey(file: File): String {
        return try { file.canonicalPath } catch (_: Throwable) { file.absolutePath }
    }

    private fun getSession(file: File, password: String?): Session {
        val key = getSessionKey(file)
        return sessions.compute(key) { _, existing ->
            if (existing != null && existing.password == password) {
                existing
            } else {
                existing?.close()
                Session(file, password)
            }
        }!!
    }

    /**
     * Extracts target entry and optionally subsequent lookahead entries sequentially.
     * Advances the stream cursor forward without rewinding. If a backward request occurs,
     * it resets the stream to the beginning and catches up.
     */
    suspend fun extractSequential(
        file: File,
        targetEntryName: String,
        password: String?,
        lookahead: Int = 0,
        onEntryExtracted: (name: String, stream: InputStream) -> Unit
    ): Boolean {
        val session = getSession(file, password)

        return session.lock.withLock {
            session.lastAccessTime = System.currentTimeMillis()
            session.openIfNeeded()
            val sz = session.sevenZ ?: return@withLock false

            val normalizedTarget = targetEntryName.replace('\\', '/')
            val nfcTarget = java.text.Normalizer.normalize(normalizedTarget, java.text.Normalizer.Form.NFC)

            val targetIdx = session.entries.indexOfFirst {
                val candidate = it.name.replace('\\', '/')
                it.name == targetEntryName ||
                        it.name == normalizedTarget ||
                        candidate == normalizedTarget ||
                        java.text.Normalizer.normalize(candidate, java.text.Normalizer.Form.NFC) == nfcTarget
            }
            if (targetIdx < 0) return@withLock false

            // If target is behind current cursor, solid stream cannot seek backwards -> reset to start
            if (targetIdx <= session.currentEntryIndex) {
                session.reset()
            }

            val currentSz = session.sevenZ ?: return@withLock false
            var targetFound = false
            var lookaheadRemaining = lookahead
            val discardBuffer = ByteArray(32 * 1024)

            while (true) {
                val entry = currentSz.nextEntry ?: break
                session.currentEntryIndex++

                val isDir = entry.isDirectory
                val entryName = entry.name
                val isTarget = (session.currentEntryIndex == targetIdx)

                if (isTarget) {
                    targetFound = true
                    if (!isDir) {
                        currentSz.getInputStream(entry).use { stream ->
                            onEntryExtracted(entryName, stream)
                            while (stream.read(discardBuffer) != -1) {
                                // drain remaining
                            }
                        }
                    }
                } else if (!targetFound) {
                    // In solid compression, intermediate bytes must be decoded anyway to reach target.
                    // Opportunistically caching them saves work and turns O(N^2) seek into O(N) linear work.
                    if (!isDir && ZipArchiveManager.isImageFile(entryName)) {
                        currentSz.getInputStream(entry).use { stream ->
                            onEntryExtracted(entryName, stream)
                            while (stream.read(discardBuffer) != -1) {
                                // drain remaining
                            }
                        }
                    } else if (!isDir) {
                        currentSz.getInputStream(entry).use { stream ->
                            while (stream.read(discardBuffer) != -1) {
                                // discard
                            }
                        }
                    }
                } else if (targetFound && lookaheadRemaining > 0) {
                    if (!isDir && ZipArchiveManager.isImageFile(entryName)) {
                        currentSz.getInputStream(entry).use { stream ->
                            onEntryExtracted(entryName, stream)
                            while (stream.read(discardBuffer) != -1) {
                                // drain remaining
                            }
                        }
                        lookaheadRemaining--
                    } else if (!isDir) {
                        currentSz.getInputStream(entry).use { stream ->
                            while (stream.read(discardBuffer) != -1) {
                                // discard
                            }
                        }
                    }
                }

                if (targetFound && lookaheadRemaining <= 0) {
                    break
                }
            }

            targetFound
        }
    }

    /**
     * Directly decodes and stores a downsampled WebP thumbnail into [thumbnailDiskCache]
     * using the active forward 7z stream session.
     *
     * Opportunistically caches intermediate images encountered before [targetEntryName]
     * into [thumbnailDiskCache], turning O(N^2) seek penalty into an optimal O(N) linear pass.
     */
    suspend fun extractThumbnail(
        file: File,
        targetEntryName: String,
        targetSizePx: Int,
        password: String?,
        thumbnailDiskCache: ThumbnailDiskCache,
        lookahead: Int = 0
    ): File? {
        val session = getSession(file, password)

        return session.lock.withLock {
            session.lastAccessTime = System.currentTimeMillis()
            session.openIfNeeded()
            val sz = session.sevenZ ?: return@withLock null

            val normalizedTarget = targetEntryName.replace('\\', '/')
            val nfcTarget = java.text.Normalizer.normalize(normalizedTarget, java.text.Normalizer.Form.NFC)

            val targetIdx = session.entries.indexOfFirst {
                val candidate = it.name.replace('\\', '/')
                it.name == targetEntryName ||
                        it.name == normalizedTarget ||
                        candidate == normalizedTarget ||
                        java.text.Normalizer.normalize(candidate, java.text.Normalizer.Form.NFC) == nfcTarget
            }
            if (targetIdx < 0) return@withLock null

            // If target is behind current cursor, solid stream cannot seek backwards -> reset to start
            if (targetIdx <= session.currentEntryIndex) {
                session.reset()
            }

            val currentSz = session.sevenZ ?: return@withLock null
            var resultFile: File? = null
            var lookaheadRemaining = lookahead
            val discardBuffer = ByteArray(32 * 1024)

            while (true) {
                val entry = currentSz.nextEntry ?: break
                session.currentEntryIndex++

                val isDir = entry.isDirectory
                val entryName = entry.name
                val isTarget = (session.currentEntryIndex == targetIdx)

                if (isTarget) {
                    if (!isDir) {
                        currentSz.getInputStream(entry).use { stream ->
                            resultFile = thumbnailDiskCache.getOrPut(file, entryName, targetSizePx, password) { stream }
                        }
                    }
                } else if (session.currentEntryIndex < targetIdx) {
                    if (!isDir) {
                        if (ZipArchiveManager.isImageFile(entryName) &&
                            thumbnailDiskCache.get(file, entryName, targetSizePx, password) == null
                        ) {
                            // Opportunistically save intermediate thumbnails to eliminate backward resets
                            currentSz.getInputStream(entry).use { stream ->
                                thumbnailDiskCache.getOrPut(file, entryName, targetSizePx, password) { stream }
                            }
                        } else {
                            currentSz.getInputStream(entry).use { stream ->
                                while (stream.read(discardBuffer) != -1) {
                                    // discard
                                }
                            }
                        }
                    }
                } else if (session.currentEntryIndex > targetIdx && lookaheadRemaining > 0) {
                    if (!isDir && ZipArchiveManager.isImageFile(entryName)) {
                        currentSz.getInputStream(entry).use { stream ->
                            thumbnailDiskCache.getOrPut(file, entryName, targetSizePx, password) { stream }
                        }
                        lookaheadRemaining--
                    } else if (!isDir) {
                        currentSz.getInputStream(entry).use { stream ->
                            while (stream.read(discardBuffer) != -1) {
                                // discard
                            }
                        }
                    }
                }

                if (resultFile != null && lookaheadRemaining <= 0) {
                    break
                }
            }

            resultFile
        }
    }

    /**
     * Pre-warms the session and verifies whether the password can open the archive.
     * Returns true if session is opened successfully and kept warm in memory.
     */
    fun prewarmSession(file: File, password: String?): Boolean {
        val key = getSessionKey(file)
        val session = getSession(file, password)
        return try {
            session.openIfNeeded()
            session.sevenZ != null
        } catch (_: Throwable) {
            sessions.remove(key)?.close()
            false
        }
    }

    /**
     * Returns the pre-parsed image entry list from the active warm session if available.
     */
    fun getEntries(file: File, password: String?): List<ArchiveEntryInfo>? {
        val key = getSessionKey(file)
        val session = sessions[key] ?: return null
        if (session.password != password) return null
        if (session.entries.isEmpty()) return null

        val naturalOrderComparator = NaturalOrderComparator()
        return session.entries
            .asSequence()
            .filter { !it.isDirectory }
            .filter { !ZipArchiveManager.isIgnoredFile(it.name) }
            .filter { ZipArchiveManager.isImageFile(it.name) }
            .map { entry ->
                ArchiveEntryInfo(
                    name = entry.name,
                    uncompressedSize = entry.size,
                    isEncrypted = entry.contentMethods?.any { it.method == org.apache.commons.compress.archivers.sevenz.SevenZMethod.AES256SHA256 } == true
                )
            }
            .sortedWith { a, b -> naturalOrderComparator.compare(a.name, b.name) }
            .toList()
    }

    /**
     * Closes the active session for a specific file.
     */
    fun closeSession(file: File) {
        val key = getSessionKey(file)
        sessions.remove(key)?.close()
    }

    /**
     * Closes all active sessions.
     */
    fun closeAll() {
        for (s in sessions.values) {
            s.close()
        }
        sessions.clear()
    }

    /**
     * Reaps idle sessions exceeding [idleTimeoutMs].
     */
    fun reapIdleSessions() {
        val now = System.currentTimeMillis()
        val expired = sessions.entries.filter { now - it.value.lastAccessTime > idleTimeoutMs }
        for (e in expired) {
            if (sessions.remove(e.key, e.value)) {
                e.value.close()
            }
        }
    }
}
