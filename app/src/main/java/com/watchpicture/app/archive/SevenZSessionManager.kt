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
        val key = getSessionKey(file)
        val session = sessions.computeIfAbsent(key) { Session(file, password) }

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

            while (true) {
                val entry = currentSz.nextEntry ?: break
                session.currentEntryIndex++

                val isDir = entry.isDirectory
                val entryName = entry.name
                val isTarget = (session.currentEntryIndex == targetIdx)

                if (isTarget) {
                    targetFound = true
                    if (!isDir) {
                        val stream = currentSz.getInputStream(entry)
                        onEntryExtracted(entryName, stream)
                    }
                } else if (!targetFound) {
                    // In solid compression, intermediate bytes must be decoded anyway to reach target.
                    // Opportunistically caching them saves work and turns O(N^2) seek into O(N) linear work.
                    if (!isDir && ZipArchiveManager.isImageFile(entryName)) {
                        val stream = currentSz.getInputStream(entry)
                        onEntryExtracted(entryName, stream)
                    }
                } else if (targetFound && lookaheadRemaining > 0) {
                    if (!isDir && ZipArchiveManager.isImageFile(entryName)) {
                        val stream = currentSz.getInputStream(entry)
                        onEntryExtracted(entryName, stream)
                        lookaheadRemaining--
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
