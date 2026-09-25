package com.watchpicture.app.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Global coordinator for archive image extraction.
 *
 * Guarantees:
 * 1. Single active decompression thread per archive file, completely eliminating
 *    CPU thread contention, cache trashing, thermal throttling, and 100% CPU lockups.
 * 2. High-priority on-demand extraction for the currently viewed viewport page.
 * 3. Single-pass forward stream extraction for solid 7z archives:
 *    As the stream advances to locate the target entry, all encountered intermediate
 *    image entries are opportunistically extracted and cached to disk, turning O(N^2)
 *    solid decompression into O(N) linear single-pass. Subsequent pages hit disk with 0ms latency.
 */
class ArchiveExtractionCoordinator(
    private val zipArchiveManager: ZipArchiveManager,
    private val archiveDiskCache: ArchiveDiskCache,
    private val powerThermalManager: PowerThermalManager? = null,
    val sevenZSessionManager: SevenZSessionManager = zipArchiveManager.sevenZManager.sessionManager
) {
    companion object {
        // Disabled coordinator lookahead window to prevent background coroutines from contending for decompression locks.
        // Lookahead is handled by the viewer viewport layer on a per-page debounced basis.
        private const val SOLID_LOOKAHEAD_WINDOW = 0

        // Batch sweep cooling interval and delay to avoid SoC thermal throttling
        private const val BATCH_COOLING_INTERVAL = 5
        private const val BATCH_COOLING_DELAY_MS = 32L

        // How long to keep the native 7z solid block cache alive after the last extraction.
        // Sequential paging reuses the same solid block; purging too aggressively (e.g. 3s) forces
        // a full re-decompression + AES decryption of the whole solid block on the next page turn,
        // which is exactly the "decryption takes too long" symptom. 30s keeps normal paging fast;
        // RAM pressure is handled by WatchPictureApp.onTrimMemory (closing sessions frees the buffer).
        private const val DEFAULT_PURGE_DELAY_MS = 30_000L
        // Under thermal/battery throttling, reclaim RAM sooner.
        private const val THROTTLED_PURGE_DELAY_MS = 3_000L
    }

    private val archiveLocks = ConcurrentHashMap<String, Mutex>()
    private val activeSweepJob = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)
    private val sweepPauseCounter = java.util.concurrent.atomic.AtomicInteger(0)

    val isSweepPaused: Boolean
        get() = sweepPauseCounter.get() > 0

    /**
     * Cooperatively pauses background thumbnail sweep to yield locks to interactive foreground viewport.
     * Uses reference counting so multiple concurrent foreground requests keep the sweep paused until all finish.
     */
    fun pauseBackgroundSweep() {
        sweepPauseCounter.incrementAndGet()
    }

    /**
     * Resumes background thumbnail sweep after foreground request is serviced.
     */
    fun resumeBackgroundSweep() {
        sweepPauseCounter.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /**
     * Completely cancels the active background thumbnail sweep and resets pause state.
     */
    fun cancelBackgroundSweep() {
        sweepPauseCounter.set(0)
        activeSweepJob.getAndSet(null)?.cancel()
    }

    private val purgeJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * Schedules a delayed purge of native solid block cache for [file] (default 30 seconds of inactivity).
     * The buffer is only freed after a long idle, so rapid sequential paging hits the cached solid
     * block instead of re-decompressing it. On low memory the session is closed outright (frees it).
     */
    fun scheduleCachePurge(file: File, delayMs: Long = DEFAULT_PURGE_DELAY_MS) {
        val path = try { file.canonicalPath } catch (_: Throwable) { file.absolutePath }
        purgeJobs[path]?.cancel()
        purgeJobs[path] = kotlinx.coroutines.CoroutineScope(ArchiveDispatchers.decompressDispatcher).launch {
            kotlinx.coroutines.delay(delayMs)
            // Re-check cancellation after delay: a cancel() racing with delay resumption must not
            // proceed to purge (and tear down native cache) while a fresh extraction may be running.
            currentCoroutineContext().ensureActive()
            sevenZSessionManager.purgeCache(file)
            purgeJobs.remove(path)
        }
    }

    /**
     * Cancels any pending purge for [file] (called when new extraction starts).
     */
    fun cancelCachePurge(file: File) {
        val path = try { file.canonicalPath } catch (_: Throwable) { file.absolutePath }
        purgeJobs.remove(path)?.cancel()
    }

    private fun getLockFor(file: File): Mutex {
        val path = try { file.canonicalPath } catch (_: Throwable) { file.absolutePath }
        return archiveLocks.computeIfAbsent(path) { Mutex() }
    }

    /**
     * Extracts a single entry from [file] on demand with full disk caching and opportunistic lookahead.
     * Guarantees single-threaded execution per archive to avoid multi-thread solid decompressor conflicts.
     */
    suspend fun extract(
        file: File,
        entryName: String,
        password: String? = null
    ): File = withContext(ArchiveDispatchers.decompressDispatcher) {
        val t0 = System.currentTimeMillis()
        // Fast path 1: Instant 0ms disk cache hit
        val cached = archiveDiskCache.get(file, entryName, password)
        if (cached != null && cached.exists() && cached.length() > 0L) {
            com.watchpicture.app.util.AppLog.i("Extract", "Disk hit ${entryName} in ${System.currentTimeMillis() - t0}ms")
            return@withContext cached
        }

        // Serialized extraction under per-archive mutex
        val mutex = getLockFor(file)
        mutex.withLock {
            // Fast path 2: Re-check after acquiring lock (previous extraction might have cached it)
            val recheck = archiveDiskCache.get(file, entryName, password)
            if (recheck != null && recheck.exists() && recheck.length() > 0L) {
                com.watchpicture.app.util.AppLog.i("Extract", "Disk hit (recheck) ${entryName} in ${System.currentTimeMillis() - t0}ms")
                return@withLock recheck
            }

            cancelCachePurge(file)
            val is7z = ZipArchiveManager.isSevenZFile(file)
            val result = if (is7z) {
                extractSevenZWithOpportunisticCache(file, entryName, password)
            } else {
                extractZipEntry(file, entryName, password)
            }
            com.watchpicture.app.util.AppLog.i("Extract", "Extracted ${entryName} in ${System.currentTimeMillis() - t0}ms")
            if (is7z) {
                val purgeDelay = if (powerThermalManager?.isThrottled == true) {
                    THROTTLED_PURGE_DELAY_MS
                } else {
                    DEFAULT_PURGE_DELAY_MS
                }
                scheduleCachePurge(file, purgeDelay)
            }
            result
        }
    }

    /**
     * Extracts a high-priority entry (currently requested by user viewport) and returns
     * the cached File on disk. If already cached, returns immediately in 0ms without acquiring locks.
     */
    suspend fun extractHighPriority(
        file: File,
        entryName: String,
        password: String?
    ): File {
        // Yield background sweep to immediately free the session lock and CPU cores
        pauseBackgroundSweep()
        try {
            return extract(file, entryName, password)
        } finally {
            resumeBackgroundSweep()
        }
    }

    private fun extractZipEntry(file: File, entryName: String, password: String?): File {
        return archiveDiskCache.getOrPut(file, entryName, password) {
            zipArchiveManager.getEntryInputStream(file, entryName, password)
        }
    }

    private suspend fun extractSevenZWithOpportunisticCache(
        file: File,
        targetEntryName: String,
        password: String?
    ): File {
        val lookahead = if (powerThermalManager?.isThrottled == true) 0 else SOLID_LOOKAHEAD_WINDOW

        // Fast path 1: Official Native 7-Zip ANSI-C LZMA SDK with C-level solid block cache & zero-copy direct file dump
        if (Native7z.isAvailable) {
            val cached = archiveDiskCache.get(file, targetEntryName, password)
            if (cached != null && cached.exists() && cached.length() > 0L) {
                return cached
            }

            val extractedFile = runCatching {
                archiveDiskCache.putDirectSuspend(file, targetEntryName, password) { tempTargetFile ->
                    val success = sevenZSessionManager.extractToFile(
                        file = file,
                        targetEntryName = targetEntryName,
                        password = password,
                        destination = tempTargetFile,
                        lookahead = 0
                    )
                    if (!success) {
                        throw java.io.IOException("Native7z extractToFile returned false for $targetEntryName")
                    }
                }
            }.getOrNull()

            if (extractedFile != null && extractedFile.exists() && extractedFile.length() > 0L) {
                if (lookahead > 0) {
                    val nextEntries = sevenZSessionManager.getNextMediaEntries(file, targetEntryName, password, lookahead)
                    if (nextEntries.isNotEmpty()) {
                        // Background lookahead on the low-priority dispatcher; cancelled via
                        // activeSweepJob / purgeJobs management on session close.
                        kotlinx.coroutines.CoroutineScope(ArchiveDispatchers.backgroundSweepDispatcher).launch {
                            for (nextEntry in nextEntries) {
                                if (archiveDiskCache.get(file, nextEntry, password) == null) {
                                    runCatching {
                                        archiveDiskCache.putDirectSuspend(file, nextEntry, password) { tempTargetFile ->
                                            sevenZSessionManager.extractToFile(file, nextEntry, password, tempTargetFile, 0)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return extractedFile
            }
        }

        // Fast path 2: Native C/C++ libarchive JNI with direct zero-copy write (only for unencrypted archives as libarchive lacks 7z AES)
        if (LibArchiveExtractor.isAvailable && password.isNullOrEmpty()) {
            val nativeSuccess = LibArchiveExtractor.extractDirectWithOpportunisticCache(
                file = file,
                targetEntryName = targetEntryName,
                password = password,
                maxLookahead = lookahead
            ) { name, writeToStream ->
                archiveDiskCache.putDirect(file, name, password) { tempTargetFile ->
                    java.io.FileOutputStream(tempTargetFile).use { fos ->
                        writeToStream(fos)
                    }
                }
            }

            if (nativeSuccess) {
                val cached = archiveDiskCache.get(file, targetEntryName, password)
                if (cached != null && cached.exists() && cached.length() > 0L) {
                    return cached
                }
            }
        }

        // Fast path 2: Session-based forward streaming (O(1) sequential progression, no rewinding from 0)
        val sessionSuccess = sevenZSessionManager.extractSequential(
            file = file,
            targetEntryName = targetEntryName,
            password = password,
            lookahead = lookahead
        ) { name, stream ->
            archiveDiskCache.getOrPut(file, name, password) { stream }
        }

        if (sessionSuccess) {
            val cached = archiveDiskCache.get(file, targetEntryName, password)
            if (cached != null && cached.exists() && cached.length() > 0L) {
                return cached
            }
        }

        // Fast path 3 / Fallback: One-off Apache Commons Compress
        val normalizedTarget = targetEntryName.replace('\\', '/')
        val nfcTarget = java.text.Normalizer.normalize(normalizedTarget, java.text.Normalizer.Form.NFC)

        val builder = SevenZFile.builder().setFile(file)
        if (!password.isNullOrEmpty()) {
            builder.setPassword(password)
        }

        var resultFile: File? = null
        var lookaheadRemaining = lookahead
        var targetFound = false

        builder.get().use { sevenZ ->
            while (true) {
                val entry = sevenZ.nextEntry ?: break
                if (entry.isDirectory) continue

                val entryName = entry.name
                val entryNormalized = entryName.replace('\\', '/')
                val isImage = ZipArchiveManager.isImageFile(entryName)

                val isTarget = (entryName == targetEntryName ||
                        entryNormalized == normalizedTarget ||
                        java.text.Normalizer.normalize(entryNormalized, java.text.Normalizer.Form.NFC) == nfcTarget)

                if (isTarget) {
                    val extracted = archiveDiskCache.getOrPut(file, entryName, password) {
                        sevenZ.getInputStream(entry)
                    }
                    resultFile = extracted
                    targetFound = true
                } else if (isImage) {
                    val isAlreadyCached = archiveDiskCache.get(file, entryName, password) != null
                    if (!isAlreadyCached) {
                        if (!targetFound || lookaheadRemaining > 0) {
                            runCatching {
                                archiveDiskCache.getOrPut(file, entryName, password) {
                                    sevenZ.getInputStream(entry)
                                }
                            }
                            if (targetFound) {
                                lookaheadRemaining--
                            }
                        }
                    }
                }

                if (targetFound && lookaheadRemaining <= 0) {
                    break
                }
            }
        }

        return resultFile
            ?: archiveDiskCache.get(file, targetEntryName, password)
            ?: throw NoSuchElementException("Entry '$targetEntryName' not found in 7z archive ${file.name}")
    }

    /**
     * Extracts a thumbnail entry directly into [ThumbnailDiskCache] without dumping the full uncompressed
     * file to [ArchiveDiskCache], reducing flash write amplification by 99%+.
     */
    suspend fun extractThumbnailDirect(
        file: File,
        entryName: String,
        targetSizePx: Int,
        password: String?,
        thumbnailDiskCache: ThumbnailDiskCache
    ): File = extractThumbnailDirectResult(
        file = file,
        entryName = entryName,
        targetSizePx = targetSizePx,
        password = password,
        thumbnailDiskCache = thumbnailDiskCache,
        keepBitmapInMemory = false
    ).also { it.lease?.close() } // This File-only variant does not hand ownership to a reader.
        .file

    suspend fun extractThumbnailDirectResult(
        file: File,
        entryName: String,
        targetSizePx: Int,
        password: String?,
        thumbnailDiskCache: ThumbnailDiskCache,
        keepBitmapInMemory: Boolean = false
    ): ThumbnailResult = withContext(ArchiveDispatchers.decompressDispatcher) {
        cancelCachePurge(file)
        // Fast path 1: Instant cache hit in thumbnailDiskCache
        val cachedThumb = thumbnailDiskCache.get(file, entryName, targetSizePx, password)
        if (cachedThumb != null && cachedThumb.exists() && cachedThumb.length() > 0L) {
            return@withContext ThumbnailResult(cachedThumb, null)
        }

        // Fast path 2: If full image is already cached in archiveDiskCache, downsample from it
        val cachedFull = archiveDiskCache.get(file, entryName, password)
        if (cachedFull != null && cachedFull.exists() && cachedFull.length() > 0L) {
            return@withContext thumbnailDiskCache.getOrPutResult(file, entryName, targetSizePx, password, keepBitmapInMemory) {
                java.io.FileInputStream(cachedFull)
            }
        }

        // Fast path 3: If target is in a local directory, read directly without archive mutex
        if (file.isDirectory) {
            val cleanName = entryName.trimStart('/', '\\')
            val directEntry = File(file, cleanName)
            val resolved = if (directEntry.exists() && directEntry.isFile) {
                directEntry
            } else {
                val alt = File(file, cleanName.replace('\\', '/'))
                if (alt.exists() && alt.isFile) alt else null
            }
            if (resolved != null) {
                return@withContext thumbnailDiskCache.getOrPutResult(file, entryName, targetSizePx, password, keepBitmapInMemory) {
                    java.io.FileInputStream(resolved)
                }
            }
        }

        // Yield background sweep to immediately free the session lock and CPU cores for foreground viewport rendering
        pauseBackgroundSweep()
        try {
            val mutex = getLockFor(file)
            mutex.withLock {
                val recheckThumb = thumbnailDiskCache.get(file, entryName, targetSizePx, password)
                if (recheckThumb != null && recheckThumb.exists() && recheckThumb.length() > 0L) {
                    return@withLock ThumbnailResult(recheckThumb, null)
                }

                if (ZipArchiveManager.isSevenZFile(file)) {
                    val thumbResult = sevenZSessionManager.extractThumbnailResult(
                        file = file,
                        targetEntryName = entryName,
                        targetSizePx = targetSizePx,
                        password = password,
                        thumbnailDiskCache = thumbnailDiskCache,
                        lookahead = 0,
                        keepBitmapInMemory = keepBitmapInMemory
                    )
                    val isSuccess = thumbResult != null && (
                        (thumbResult.file.exists() && thumbResult.file.length() > 0L) ||
                        (thumbResult.bitmap != null && !thumbResult.bitmap.isRecycled)
                    )
                    if (isSuccess) {
                        return@withLock thumbResult
                    }
                }

                thumbnailDiskCache.getOrPutResult(file, entryName, targetSizePx, password, keepBitmapInMemory) {
                    zipArchiveManager.getEntryInputStream(file, entryName, password)
                }
            }
        } finally {
            resumeBackgroundSweep()
        }
    }

    /**
     * Generates thumbnails for a list of entries in a single sequential pass through the 7z archive,
     * avoiding multiple re-scans of solid blocks and ensuring high UI responsiveness in thumbnail grids.
     */
    suspend fun startBatchThumbnailSweep(
        file: File,
        entryNames: List<String>,
        targetSizePx: Int,
        password: String?,
        thumbnailDiskCache: ThumbnailDiskCache,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ) = withContext(ArchiveDispatchers.backgroundSweepDispatcher) {
        if (entryNames.isEmpty()) return@withContext
        if (powerThermalManager?.isThrottled == true) {
            return@withContext
        }

        cancelCachePurge(file)
        val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        activeSweepJob.set(currentJob)
        try {
            val uncached = entryNames.filter { name ->
                thumbnailDiskCache.get(file, name, targetSizePx, password) == null
            }
            if (uncached.isEmpty()) return@withContext

            if (ZipArchiveManager.isSevenZFile(file)) {
                val rawPhysical = sevenZSessionManager.getPhysicalEntryNames(file, password)
                val physicalOrderMap = rawPhysical?.mapIndexed { idx, it ->
                    it.replace('\\', '/') to idx
                }?.toMap() ?: emptyMap()

                // Sort uncached targets strictly ascending according to physical order in the 7z archive
                val sortedUncached = uncached.sortedBy { target ->
                    val norm = target.replace('\\', '/')
                    physicalOrderMap[norm] ?: physicalOrderMap[target] ?: Int.MAX_VALUE
                }

                var count = 0
                var consecutiveGenerated = 0
                val total = sortedUncached.size
                try {
                    for (target in sortedUncached) {
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
                        if (powerThermalManager?.isThrottled == true) break

                        // Cooperative yield while foreground requests are in flight
                        while (isSweepPaused && kotlinx.coroutines.currentCoroutineContext().isActive) {
                            kotlinx.coroutines.delay(80)
                        }
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) break

                        val alreadyCached = thumbnailDiskCache.get(file, target, targetSizePx, password)
                        if (alreadyCached != null && alreadyCached.exists() && alreadyCached.length() > 0L) {
                            count++
                            consecutiveGenerated = 0
                            onProgress?.invoke(count, total)
                            continue
                        }
                        runCatching {
                            sevenZSessionManager.extractThumbnail(
                                file = file,
                                targetEntryName = target,
                                targetSizePx = targetSizePx,
                                password = password,
                                thumbnailDiskCache = thumbnailDiskCache,
                                lookahead = 0,
                                allowRewind = true
                            )
                        }
                        count++
                        consecutiveGenerated++
                        onProgress?.invoke(count, total)
                        kotlinx.coroutines.yield() // Yield so interactive UI requests get top priority!
                        if (consecutiveGenerated >= BATCH_COOLING_INTERVAL) {
                            consecutiveGenerated = 0
                            kotlinx.coroutines.delay(BATCH_COOLING_DELAY_MS)
                        }
                    }
                } finally {
                    // Don't purge immediately after the sweep: the user typically jumps straight into the
                    // viewer, which reuses the same solid block. Delay the reclaim so the first full-res
                    // page stays fast; it will be purged after idle or freed by onTrimMemory.
                    scheduleCachePurge(file)
                }
                return@withContext
            }

            val mutex = getLockFor(file)
            mutex.withLock {
                if (powerThermalManager?.isThrottled == true) return@withLock
                val targets = uncached.filter { name ->
                    thumbnailDiskCache.get(file, name, targetSizePx, password) == null
                }
                if (targets.isEmpty()) return@withLock

                var count = 0
                var consecutiveGenerated = 0
                val total = targets.size
                for (name in targets) {
                    if (powerThermalManager?.isThrottled == true) break

                    // Cooperative yield while foreground requests (Coil thumbnails / viewer) are in
                    // flight, so interactive decryption gets the CPU instead of this background sweep.
                    while (isSweepPaused && kotlinx.coroutines.currentCoroutineContext().isActive) {
                        kotlinx.coroutines.delay(80)
                    }
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) break

                    runCatching {
                        thumbnailDiskCache.getOrPut(file, name, targetSizePx, password) {
                            zipArchiveManager.getEntryInputStream(file, name, password)
                        }
                        count++
                        consecutiveGenerated++
                        onProgress?.invoke(count, total)
                    }
                    kotlinx.coroutines.yield()
                    if (consecutiveGenerated >= BATCH_COOLING_INTERVAL) {
                        consecutiveGenerated = 0
                        kotlinx.coroutines.delay(BATCH_COOLING_DELAY_MS)
                    }
                }
            }
        } finally {
            activeSweepJob.compareAndSet(currentJob, null)
        }
    }

    /**
     * Closes any active streaming sessions for [file].
     */
    fun closeSession(file: File) {
        sevenZSessionManager.closeSession(file)
    }

    /**
     * Asynchronously prefetches adjacent entries in background without blocking the UI thread.
     */
    suspend fun prefetch(
        file: File,
        targetEntryNames: List<String>,
        password: String?
    ) = withContext(ArchiveDispatchers.backgroundSweepDispatcher) {
        if (powerThermalManager?.isThrottled == true) {
            // Drop background prefetch immediately when phone is hot or in battery saver mode
            return@withContext
        }

        val uncached = targetEntryNames.filter { name ->
            archiveDiskCache.get(file, name, password) == null
        }
        if (uncached.isEmpty()) return@withContext

        val mutex = getLockFor(file)
        mutex.withLock {
            val stillUncached = uncached.filter { name ->
                archiveDiskCache.get(file, name, password) == null
            }
            if (stillUncached.isEmpty()) return@withLock

            if (ZipArchiveManager.isSevenZFile(file)) {
                // Reuse active warm SevenZSessionManager to avoid repeating 524k-round PBKDF2 key derivations
                runCatching {
                    for (name in stillUncached) {
                        sevenZSessionManager.extractSequential(file, name, password, lookahead = 0) { extractedName, stream ->
                            archiveDiskCache.getOrPut(file, extractedName, password) { stream }
                        }
                    }
                }
            } else {
                for (name in stillUncached) {
                    runCatching {
                        archiveDiskCache.getOrPut(file, name, password) {
                            zipArchiveManager.getEntryInputStream(file, name, password)
                        }
                    }
                }
            }
        }
    }
}
