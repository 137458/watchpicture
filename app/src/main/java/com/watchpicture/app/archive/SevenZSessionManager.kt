package com.watchpicture.app.archive

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
open class SevenZSessionManager(
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
        var nativeSession: Native7zArchiveSession? = null
        var nativeFailed: Boolean = false
        var sevenZ: SevenZFile? = null
        var entries: List<SevenZArchiveEntry> = emptyList()
        var currentEntryIndex: Int = -1
        var lastAccessTime: Long = System.currentTimeMillis()

        /**
         * Password that has already been proven correct for this session. For solid encrypted
         * archives, verifying a password decompresses the whole solid block (potentially hundreds
         * of MB), which is the dominant cost of opening an encrypted pack. Once proven, re-verifying
         * the same password in the same session (e.g. re-entering a pack) can skip that full block
         * decompression entirely.
         */
        @Volatile
        var verifiedPassword: String? = null

        /**
         * Whether this archive encrypts its header. For header-encrypted 7z archives, a successful
         * [Native7zArchiveSession.open] (i.e. `SzArEx_Open`) only succeeds when the password is
         * correct, so password verification needs no full solid-block decompression at all.
         * Null until probed once.
         */
        @Volatile
        var isHeaderEncrypted: Boolean? = null

        // Guards the external final-close path so a session that has been handed off
        // for closure is closed at most once, even under concurrent close callers.
        @Volatile
        private var closeRequested: Boolean = false

        fun markClosed(): Boolean {
            if (closeRequested) return false
            closeRequested = true
            return true
        }

        fun openIfNeeded() {
            if (nativeSession != null && !nativeFailed) {
                return
            }
            if (sevenZ != null) {
                return
            }
            if (!nativeFailed && nativeSession == null) {
                if (Native7z.isAvailable) {
                    try {
                        val ns = Native7zArchiveSession.open(file.absolutePath, password)
                        if (ns != null && ns.entries.isNotEmpty()) {
                            nativeSession = ns
                            // Probe header encryption once, using a deliberately wrong password:
                            // if the header itself is encrypted, SzArEx_Open fails without the correct
                            // password, proving the password during open. For content-only encryption
                            // the header opens regardless, so verification must still decompress data.
                            // Probed via the raw JNI entry point to avoid ERROR-log noise on the
                            // expected probe failure.
                            if (isHeaderEncrypted == null && password != null) {
                                val probeOk = runCatching {
                                    val probeHandle = Native7z.nativeOpen(file.absolutePath, "invalid-probe-password")
                                    if (probeHandle != 0L) {
                                        Native7z.nativeClose(probeHandle)
                                        true
                                    } else {
                                        false
                                    }
                                }.getOrDefault(false)
                                isHeaderEncrypted = !probeOk
                            }
                            com.watchpicture.app.util.AppLog.d("7zSession", "Native7z session opened for ${file.name} with ${ns.entries.size} entries")
                            return
                        } else {
                            com.watchpicture.app.util.AppLog.w("7zSession", "Native7zArchiveSession.open returned null or empty for ${file.name}")
                        }
                    } catch (t: Throwable) {
                        com.watchpicture.app.util.AppLog.w("7zSession", "Native7z open failed, fallback to Java SevenZFile: ${t.message}")
                    }
                } else {
                    com.watchpicture.app.util.AppLog.w("7zSession", "Native7z.isAvailable is false for ${file.name}")
                }
            }

            if (sevenZ == null) {
                openJavaSevenZ()
            }
        }

        fun openJavaSevenZ() {
            val builder = SevenZFile.builder().setFile(file)
            if (!password.isNullOrEmpty()) {
                builder.setPassword(password)
            }
            val instance = builder.get()
            sevenZ = instance
            entries = instance.entries.toList()
            currentEntryIndex = -1
            com.watchpicture.app.util.AppLog.d("7zSession", "Java SevenZFile opened for ${file.name} with ${entries.size} entries")
        }

        fun markNativeFailed() {
            nativeFailed = true
            runCatching { nativeSession?.close() }
            nativeSession = null
            if (sevenZ == null) {
                openJavaSevenZ()
            }
        }

        fun purgeCache() {
            runCatching { nativeSession?.purgeCache() }
        }

        fun reset() {
            if (nativeSession != null && !nativeFailed) {
                // Native session has random access via solid block cache, no stream rewind needed!
                return
            }
            close()
            openIfNeeded()
        }

        fun close() {
            runCatching { nativeSession?.close() }
            nativeSession = null
            runCatching { sevenZ?.close() }
            sevenZ = null
            entries = emptyList()
            currentEntryIndex = -1
            verifiedPassword = null
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
        var replaced: Session? = null
        val session = sessions.compute(key) { _, existing ->
            if (existing != null && existing.password == password) {
                existing
            } else {
                replaced = existing
                Session(file, password)
            }
        }!!
        // The replaced old session must be closed *outside* the map compute callback so a
        // high-frequency password switch never blocks other bins' readers/writers behind it.
        replaced?.let { closeSessionNow(it) }
        return session
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
        allowRewind: Boolean = true,
        onEntryExtracted: (name: String, stream: InputStream) -> Unit
    ): Boolean {
        val session = getSession(file, password)

        return session.lock.withLock {
            session.lastAccessTime = System.currentTimeMillis()
            session.openIfNeeded()

            val native = session.nativeSession
            if (native != null && !session.nativeFailed) {
                val targetEntry = native.findEntry(targetEntryName)
                if (targetEntry != null) {
                    val directBuffer = native.extractToDirectBuffer(targetEntry.index)
                    if (directBuffer != null) {
                        ByteBufferInputStream(directBuffer).use { stream ->
                            onEntryExtracted(targetEntry.path, stream)
                        }

                        if (lookahead > 0) {
                            val nextEntries = native.entries
                                .asSequence()
                                .filter { it.index > targetEntry.index && !it.isDirectory }
                                .filter { !ZipArchiveManager.isIgnoredFile(it.path) }
                                .filter { ZipArchiveManager.isImageFile(it.path) }
                                .take(lookahead)
                                .toList()

                            for (next in nextEntries) {
                                val nextBuffer = native.extractToDirectBuffer(next.index)
                                if (nextBuffer != null) {
                                    ByteBufferInputStream(nextBuffer).use { stream ->
                                        onEntryExtracted(next.path, stream)
                                    }
                                }
                            }
                        }
                        return@withLock true
                    }

                    val bytes = native.extractToBytes(targetEntry.index)
                    if (bytes != null) {
                        java.io.ByteArrayInputStream(bytes).use { stream ->
                            onEntryExtracted(targetEntry.path, stream)
                        }

                        if (lookahead > 0) {
                            val nextEntries = native.entries
                                .asSequence()
                                .filter { it.index > targetEntry.index && !it.isDirectory }
                                .filter { !ZipArchiveManager.isIgnoredFile(it.path) }
                                .filter { ZipArchiveManager.isImageFile(it.path) }
                                .take(lookahead)
                                .toList()

                            for (next in nextEntries) {
                                val nextBytes = native.extractToBytes(next.index)
                                if (nextBytes != null) {
                                    java.io.ByteArrayInputStream(nextBytes).use { stream ->
                                        onEntryExtracted(next.path, stream)
                                    }
                                }
                            }
                        }
                        return@withLock true
                    } else {
                        com.watchpicture.app.util.AppLog.w("7zSession", "Native extractSequential failed for $targetEntryName, smoothly falling back to Java session")
                        session.markNativeFailed()
                    }
                }
            }

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
                if (!allowRewind) {
                    return@withLock false
                }
                session.reset()
            }

            val currentSz = session.sevenZ ?: return@withLock false
            var targetFound = false
            var lookaheadRemaining = lookahead
            val discardBuffer = ByteArray(32 * 1024)

            try {
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
                                    // fast discard non-image entries
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
            } catch (t: Throwable) {
                session.reset()
                throw t
            }
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
        lookahead: Int = 0,
        allowRewind: Boolean = true
    ): File? = extractThumbnailResult(
        file = file,
        targetEntryName = targetEntryName,
        targetSizePx = targetSizePx,
        password = password,
        thumbnailDiskCache = thumbnailDiskCache,
        lookahead = lookahead,
        keepBitmapInMemory = false,
        allowRewind = allowRewind
    )?.file

    /**
     * Directly decodes and stores a downsampled WebP thumbnail into [thumbnailDiskCache]
     * using the active forward 7z stream session.
     *
     * Optionally returns the in-memory downsampled [android.graphics.Bitmap] via [ThumbnailResult]
     * to eliminate disk decode overhead for first-time display.
     *
     * Opportunistically caches intermediate images encountered before [targetEntryName]
     * into [thumbnailDiskCache], turning O(N^2) seek penalty into an optimal O(N) linear pass.
     */
    open suspend fun extractThumbnailResult(
        file: File,
        targetEntryName: String,
        targetSizePx: Int,
        password: String?,
        thumbnailDiskCache: ThumbnailDiskCache,
        lookahead: Int = 0,
        keepBitmapInMemory: Boolean = false,
        allowRewind: Boolean = true
    ): ThumbnailResult? {
        val session = getSession(file, password)

        return session.lock.withLock {
            session.lastAccessTime = System.currentTimeMillis()
            session.openIfNeeded()

            val native = session.nativeSession
            if (native != null && !session.nativeFailed) {
                val targetEntry = native.findEntry(targetEntryName)
                if (targetEntry != null) {
                    val directBuffer = native.extractToDirectBuffer(targetEntry.index)
                    if (directBuffer != null) {
                        val result = thumbnailDiskCache.getOrPutResultFromByteBuffer(
                            zipFile = file,
                            entryName = targetEntryName,
                            targetSizePx = targetSizePx,
                            password = password,
                            keepBitmapInMemory = keepBitmapInMemory,
                            buffer = directBuffer
                        )

                        if (lookahead > 0) {
                            val nextEntries = native.entries
                                .asSequence()
                                .filter { it.index > targetEntry.index && !it.isDirectory }
                                .filter { !ZipArchiveManager.isIgnoredFile(it.path) }
                                .filter { ZipArchiveManager.isImageFile(it.path) }
                                .take(lookahead)
                                .toList()

                            for (next in nextEntries) {
                                if (thumbnailDiskCache.get(file, next.path, targetSizePx, password) == null) {
                                    val nextBuffer = native.extractToDirectBuffer(next.index)
                                    if (nextBuffer != null) {
                                        thumbnailDiskCache.getOrPutResultFromByteBuffer(
                                            zipFile = file,
                                            entryName = next.path,
                                            targetSizePx = targetSizePx,
                                            password = password,
                                            keepBitmapInMemory = false,
                                            buffer = nextBuffer
                                        ).lease?.close()
                                    }
                                }
                            }
                        }

                        return@withLock result
                    }

                    val bytes = native.extractToBytes(targetEntry.index)
                    if (bytes != null) {
                        val result = thumbnailDiskCache.getOrPutResultFromBytes(
                            zipFile = file,
                            entryName = targetEntryName,
                            targetSizePx = targetSizePx,
                            password = password,
                            keepBitmapInMemory = keepBitmapInMemory,
                            bytes = bytes
                        )

                        if (lookahead > 0) {
                            val nextEntries = native.entries
                                .asSequence()
                                .filter { it.index > targetEntry.index && !it.isDirectory }
                                .filter { !ZipArchiveManager.isIgnoredFile(it.path) }
                                .filter { ZipArchiveManager.isImageFile(it.path) }
                                .take(lookahead)
                                .toList()

                            for (next in nextEntries) {
                                if (thumbnailDiskCache.get(file, next.path, targetSizePx, password) == null) {
                                    val nextBytes = native.extractToBytes(next.index)
                                    if (nextBytes != null) {
                                        // Lookahead result is not handed to any reader: release its
                                        // lease immediately so the file stays evictable.
                                        thumbnailDiskCache.getOrPutResultFromBytes(
                                            zipFile = file,
                                            entryName = next.path,
                                            targetSizePx = targetSizePx,
                                            password = password,
                                            keepBitmapInMemory = false,
                                            bytes = nextBytes
                                        ).lease?.close()
                                    }
                                }
                            }
                        }

                        return@withLock result
                    } else {
                        com.watchpicture.app.util.AppLog.w("7zSession", "Native extractThumbnailResult returned null for $targetEntryName")
                    }
                }
            }

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
                if (!allowRewind) {
                    return@withLock null
                }
                com.watchpicture.app.util.AppLog.w("7zSession", "Stream rewind reset for $targetEntryName (cursor=${session.currentEntryIndex} -> target=$targetIdx)")
                session.reset()
            }

            val currentSz = session.sevenZ ?: return@withLock null
            var result: ThumbnailResult? = null
            var lookaheadRemaining = lookahead
            val discardBuffer = ByteArray(32 * 1024)

            try {
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val entry = currentSz.nextEntry ?: break
                    session.currentEntryIndex++

                    val isDir = entry.isDirectory
                    val entryName = entry.name
                    val isTarget = (session.currentEntryIndex == targetIdx)

                    if (isTarget) {
                        if (!isDir) {
                            currentSz.getInputStream(entry).use { stream ->
                                result = thumbnailDiskCache.getOrPutResult(
                                    zipFile = file,
                                    entryName = entryName,
                                    targetSizePx = targetSizePx,
                                    password = password,
                                    keepBitmapInMemory = keepBitmapInMemory
                                ) { stream }
                            }
                        }
                    } else if (session.currentEntryIndex < targetIdx) {
                        if (!isDir && ZipArchiveManager.isImageFile(entryName)) {
                            currentSz.getInputStream(entry).use { stream ->
                                // Entries walked before the target are prefetched only; their
                                // lease is released here so premature pins cannot block eviction.
                                thumbnailDiskCache.getOrPutResult(
                                    zipFile = file,
                                    entryName = entryName,
                                    targetSizePx = targetSizePx,
                                    password = password,
                                    keepBitmapInMemory = false
                                ) { stream }.lease?.close()
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

                    if (result != null && lookaheadRemaining <= 0) {
                        break
                    }
                }

                result
            } catch (t: Throwable) {
                session.reset()
                throw t
            }
        }
    }

    /**
     * Extracts target entry directly to destination file using native C zero-copy stream write
     * if native session is available. Returns true if extracted natively, false otherwise.
     */
    suspend fun extractToFile(
        file: File,
        targetEntryName: String,
        password: String?,
        destination: File,
        lookahead: Int = 0
    ): Boolean {
        val session = getSession(file, password)

        // Only the native fast path is executed while holding session.lock.
        val nativeExtracted = session.lock.withLock {
            session.lastAccessTime = System.currentTimeMillis()
            session.openIfNeeded()
            val native = session.nativeSession
            if (native != null && !session.nativeFailed) {
                val targetEntry = native.findEntry(targetEntryName)
                if (targetEntry != null) {
                    val ok = native.extractToFile(targetEntry.index, destination)
                    if (ok && destination.exists() && destination.length() > 0L) {
                        return@withLock true
                    } else {
                        com.watchpicture.app.util.AppLog.w("7zSession", "Native extractToFile failed for $targetEntryName")
                    }
                }
            }
            false
        }
        if (nativeExtracted) return true

        // Smooth Java extraction fallback if Native fails or unavailable.
        // IMPORTANT: must be invoked *outside* session.lock — extractSequential()
        // calls getSession() returning this same Session and re-acquires its
        // non-reentrant kotlinx Mutex, which would self-deadlock forever.
        extractSequential(file, targetEntryName, password, lookahead, allowRewind = true) { _, stream ->
            java.io.FileOutputStream(destination).use { fos ->
                stream.copyTo(fos)
            }
        }
        return destination.exists() && destination.length() > 0L
    }

    /**
     * Retrieves the next [count] image entries following [currentEntryName] in archive physical order.
     */
    fun getNextImageEntries(
        file: File,
        currentEntryName: String,
        password: String?,
        count: Int
    ): List<String> {
        val session = getSession(file, password)
        session.openIfNeeded()
        val native = session.nativeSession
        if (native != null) {
            val target = native.findEntry(currentEntryName) ?: return emptyList()
            return native.entries
                .asSequence()
                .filter { it.index > target.index && !it.isDirectory }
                .filter { !ZipArchiveManager.isIgnoredFile(it.path) }
                .filter { ZipArchiveManager.isImageFile(it.path) }
                .take(count)
                .map { it.path }
                .toList()
        }
        val currentSz = session.entries
        val normalized = currentEntryName.replace('\\', '/')
        val idx = currentSz.indexOfFirst {
            it.name.replace('\\', '/') == normalized
        }
        if (idx < 0) return emptyList()
        return currentSz
            .asSequence()
            .drop(idx + 1)
            .filter { !it.isDirectory }
            .filter { !ZipArchiveManager.isIgnoredFile(it.name) }
            .filter { ZipArchiveManager.isImageFile(it.name) }
            .take(count)
            .map { it.name }
            .toList()
    }

    /**
     * Quickly probes whether password can decrypt entries using lightweight native verification
     * without extracting full uncompressed images into Java byte arrays.
     *
     * Verifying a password on a solid encrypted archive decompresses the whole solid block
     * (hundreds of MB for a large pack), so once a password is proven for this session we skip
     * the re-verification and return immediately on subsequent calls with the same password.
     */
    suspend fun verifyPassword(file: File, password: String): Boolean {
        val session = getSession(file, password)
        // Fast path: this password was already proven for this session (solid block already
        // decompressed or stream positioned). Re-opening the same pack must not re-pay the cost.
        if (session.verifiedPassword == password) {
            session.lastAccessTime = System.currentTimeMillis()
            return true
        }
        return try {
            session.lock.withLock {
                session.lastAccessTime = System.currentTimeMillis()
                try {
                    session.openIfNeeded()
                } catch (_: Throwable) {
                    session.close()
                    return@withLock false
                }
                val native = session.nativeSession
                if (native != null && !session.nativeFailed) {
                    val encrypted = native.entries.firstOrNull { !it.isDirectory && ZipArchiveManager.isImageFile(it.path) }
                    if (encrypted == null) {
                        session.verifiedPassword = password
                        return@withLock true
                    }
                    // Header-encrypted archive: nativeOpen (SzArEx_Open) already proved the password
                    // by decrypting the header — verifying again would force a full solid-block
                    // decompression of the whole pack (the observed ~1 minute stall). Skip it.
                    if (session.isHeaderEncrypted == true) {
                        session.verifiedPassword = password
                        return@withLock true
                    }
                    val ok = native.verifyEntry(encrypted.index)
                    if (ok) {
                        session.verifiedPassword = password
                        return@withLock true
                    }
                    session.markNativeFailed()
                }

                // Java probe fallback
                try {
                    session.openJavaSevenZ()
                } catch (_: Throwable) {
                    session.close()
                    return@withLock false
                }
                val sz = session.sevenZ ?: return@withLock false
                val entry = sz.entries.firstOrNull { !it.isDirectory && ZipArchiveManager.isImageFile(it.name) } ?: run {
                    session.verifiedPassword = password
                    return@withLock true
                }
                try {
                    val ok = sz.getInputStream(entry).use { stream ->
                        val buf = ByteArray(16)
                        stream.read(buf) >= 0
                    }
                    if (ok) {
                        session.verifiedPassword = password
                    }
                    ok
                } catch (_: Throwable) {
                    false
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns entry names in their exact physical archive storage order.
     */
    fun getPhysicalEntryNames(file: File, password: String?): List<String>? {
        val session = getSession(file, password)
        return try {
            session.openIfNeeded()
            val native = session.nativeSession
            if (native != null) {
                native.entries.map { it.path }
            } else {
                session.entries.map { it.name }.ifEmpty { null }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns entries in their exact physical archive storage order.
     */
    fun getPhysicalEntries(file: File, password: String?): List<SevenZArchiveEntry>? {
        val session = getSession(file, password)
        return try {
            session.openIfNeeded()
            session.entries.ifEmpty { null }
        } catch (_: Throwable) {
            null
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
            session.nativeSession != null || session.sevenZ != null
        } catch (_: Throwable) {
            sessions.remove(key)?.let { closeSessionNow(it) }
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

        val naturalOrderComparator = NaturalOrderComparator()
        val native = session.nativeSession
        if (native != null) {
            if (native.entries.isEmpty()) return null
            return native.entries
                .asSequence()
                .filter { !it.isDirectory }
                .filter { !ZipArchiveManager.isIgnoredFile(it.path) }
                .filter { ZipArchiveManager.isImageFile(it.path) }
                .map { entry ->
                    ArchiveEntryInfo(
                        name = entry.path,
                        uncompressedSize = entry.size,
                        isEncrypted = !password.isNullOrEmpty()
                    )
                }
                .sortedWith { a, b -> naturalOrderComparator.compare(a.name, b.name) }
                .toList()
        }

        if (session.entries.isEmpty()) return null
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
     * Performs a final, idempotent close of a session that has already been removed from the map.
     * Acquiring [Session.lock] guarantees we never tear down native/Java handles while an in-flight
     * extraction coroutine is still using them (native memory would otherwise be freed mid-use).
     */
    private fun closeSessionNow(session: Session) {
        if (!session.markClosed()) return
        // Never block the UI thread on an actual close even though the session was already
        // removed from the map; dispatch the (lock-guarded) close to the decompressor instead.
        val isMainThread = try {
            android.os.Looper.myLooper() != null && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        } catch (_: Throwable) {
            false
        }
        val closeUnderLock: suspend () -> Unit = {
            session.lock.withLock { session.close() }
        }
        if (isMainThread) {
            kotlinx.coroutines.CoroutineScope(ArchiveDispatchers.decompressDispatcher).launch {
                closeUnderLock()
            }
        } else {
            kotlinx.coroutines.runBlocking {
                closeUnderLock()
            }
        }
    }

    /**
     * Closes the active session for a specific file.
     */
    fun closeSession(file: File) {
        val key = getSessionKey(file)
        val session = sessions.remove(key) ?: return
        closeSessionNow(session)
    }

    /**
     * Closes all active sessions.
     */
    fun closeAll() {
        val all = sessions.values.toList()
        sessions.clear()
        for (s in all) {
            closeSessionNow(s)
        }
    }

    /**
     * Purges native solid block cache for the given file to reclaim native memory immediately.
     */
    fun purgeCache(file: File) {
        val key = getSessionKey(file)
        sessions[key] ?: return
        // Held under the session lock so purge never races an in-flight native extraction.
        val isMainThread = try {
            android.os.Looper.myLooper() != null && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        } catch (_: Throwable) {
            false
        }
        if (isMainThread) {
            kotlinx.coroutines.CoroutineScope(ArchiveDispatchers.decompressDispatcher).launch {
                purgeCacheUnderLock(key)
            }
        } else {
            kotlinx.coroutines.runBlocking {
                purgeCacheUnderLock(key)
            }
        }
    }

    private suspend fun purgeCacheUnderLock(key: String) {
        sessions[key]?.lock?.withLock {
            sessions[key]?.purgeCache()
        }
    }

    /**
     * Reaps idle sessions exceeding [idleTimeoutMs].
     */
    fun reapIdleSessions() {
        val now = System.currentTimeMillis()
        val expired = sessions.entries.filter { now - it.value.lastAccessTime > idleTimeoutMs }
        for (e in expired) {
            if (sessions.remove(e.key, e.value)) {
                closeSessionNow(e.value)
            }
        }
    }
}
