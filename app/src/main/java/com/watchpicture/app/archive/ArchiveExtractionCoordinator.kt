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
import java.util.concurrent.atomic.AtomicInteger

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
/**
 * 取消不是业务失败:[runCatching] 会吞掉 [kotlinx.coroutines.CancellationException],
 * 令已取消的提取协程继续走 LibArchive→session→Commons 全档回退白烧 CPU/热预算。
 * 语义与 runCatching 一致,唯取消异常先行放行。
 */
internal inline fun <T> runCatchingUnlessCancelled(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }

/**
 * 提取协调器：前台高优先级提取与后台缩略图扫描/预取的唯一入口。
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

        // 预取让路：前台交互请求持锁期间以该间隔轮询，超时后放弃本次剩余预取（下次翻页会重新预取）
        private const val PREFETCH_YIELD_POLL_MS = 60L
        private const val PREFETCH_YIELD_TIMEOUT_MS = 1_500L

        // 固实块缓存的回收延迟，仅在「真正离开图包页面」时生效（见 ThumbnailGridScreen.onDispose）。
        // 浏览过程中不再按空闲时长回收：固实块一旦释放，下一页就得从块头整块重解
        // （SzArEx_ExtractIncremental 的 decodedSize 归零），此前每条目提取后排 30s 定时
        // 会让「读一页停一会儿再翻页」必然付出整块重解代价。整块的释放改由离开页面 /
        // 退后台 / 内存压力驱动，见 WatchPictureApp.onTrimMemory。
        private const val EXIT_PURGE_DELAY_MS = 30_000L
    }

    private val archiveLocks = ConcurrentHashMap<String, Mutex>()
    private val activeSweepJob = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)
    private val sweepPauseCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 正在等待或执行的前台交互落盘请求数。预取据此让路，避免后台预取长时间霸占归档锁，
     * 使翻页时的按需落盘排在预取之后。
     */
    private val interactiveRequestCount = java.util.concurrent.atomic.AtomicInteger(0)

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
     * Schedules a delayed purge of the native solid block cache for [file].
     *
     * 只在「真正离开图包」时调用（网格销毁且不是进入看图页），保证浏览期间固实块常驻；
     * 若用户很快重新进入图包并再次发起提取，[cancelCachePurge] 会撤销本次回收。
     */
    fun scheduleCachePurge(file: File, delayMs: Long = EXIT_PURGE_DELAY_MS) {
        val path = try { file.canonicalPath } catch (_: Throwable) { file.absolutePath }
        purgeJobs[path]?.cancel()
        purgeJobs[path] = ArchiveDispatchers.maintenanceScope.launch(ArchiveDispatchers.decompressDispatcher) {
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
            // 提取后不再排定空闲回收：浏览期间固实块必须常驻，否则读一页停超过定时阈值后
            // 翻页会从块头整块重解。回收统一由离开图包页面 / 退后台 / 内存压力触发。
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
        interactiveRequestCount.incrementAndGet()
        try {
            return extract(file, entryName, password)
        } finally {
            interactiveRequestCount.decrementAndGet()
            resumeBackgroundSweep()
        }
    }

    private fun extractZipEntry(file: File, entryName: String, password: String?): File {
        // Z3 native 引擎优先：mmap 容器 + libdeflate inflate，解压即写 fd 零中间拷贝；
        // 引擎不支持加密条目，加密与任何失败回退 java.util.zip.ZipFile 流式路径。
        if (password.isNullOrEmpty()) {
            val session = zipArchiveManager.handlePool.openNativeZipSession(file)
            if (session != null) {
                val entry = session.findEntry(entryName)
                if (entry != null && !entry.isDirectory) {
                    val extracted = runCatchingUnlessCancelled {
                        archiveDiskCache.putDirect(file, entryName, password) { tempFile ->
                            if (!session.extractToFile(entry.index, tempFile)) {
                                throw java.io.IOException("NativeZip extractToFile failed for $entryName")
                            }
                        }
                    }.getOrNull()
                    if (extracted != null && extracted.exists() && extracted.length() > 0L) {
                        return extracted
                    }
                }
            }
        }
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

            val extractedFile = runCatchingUnlessCancelled {
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
                        ArchiveDispatchers.maintenanceScope.launch(ArchiveDispatchers.backgroundSweepDispatcher) {
                            for (nextEntry in nextEntries) {
                                if (archiveDiskCache.get(file, nextEntry, password) == null) {
                                    runCatchingUnlessCancelled {
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
                            runCatchingUnlessCancelled {
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

                // 进度以「整包条目数」为分母：已缓存条目计入初始进度，避免扫描中途进度条倒缩
                var count = entryNames.size - sortedUncached.size
                var consecutiveGenerated = 0
                val total = entryNames.size
                // 扫描结束不排定回收：用户此刻仍在网格上，随后进入看图页会直接复用该固实块。
                // 整块的释放由「离开图包页面 / 退后台 / 内存压力」驱动（见 onTrimMemory）。
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
                    runCatchingUnlessCancelled {
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
                // 收尾推满进度：缺失/失败条目不计 count，但分母以整包条目数为准，
                // 不补齐会让进度芯片永远停在 N/M。全部计入时不补发，维持既有回调契约。
                if (count < total && powerThermalManager?.isThrottled != true) {
                    onProgress?.invoke(total, total)
                }
                return@withContext
            }

            // ZIP 条目互不固实：每条目独立流（native ZipFile 由 libcore 对共享 RAF 串行化读，
            // AES/ZipCrypto 路径每流独享 RandomAccessFile），ThumbnailDiskCache 为条纹锁原子写，
            // 无需 7z 固实包的单线程顺序约束；也不整批持有归档互斥锁——整批持锁会让
            // 前台交互请求在扫描让路等待期间与之互锁。按核数并行提取（调研 P1 项）。
            // 不做二次缓存过滤：同一协程内刚算出的 uncached 不会再变，重复 get() 只会白跑
            // 一次弹性回落目录扫描——全量扫描下会被放大成 O(n²) 的 stat 开销。
            val targets = uncached
            val completed = AtomicInteger(0)
            val cachedBase = entryNames.size - targets.size
            val total = entryNames.size

            // STORED 条目零拷贝直通：整个批次共用一个扫描自持的 native 会话，nativeOpen 只发生
            // 一次。不走 handlePool 池化会话——池化实例可能被 LRU 逐出 close，直通缓冲会
            // use-after-free；引擎 KDoc 也规定 direct buffer 只允许短命会话。DEFLATE 不路由
            // 直通（引擎把解压堆内存持有到 close，整包直通会无界增长），保持池化 java.util.zip
            // 流；加密包不开会话（引擎不识别加密条目，条目流由加密句柄池处理）。
            val nativeSession = if (!file.isDirectory && password.isNullOrEmpty()) {
                NativeZipSession.open(file.absolutePath)
            } else {
                null
            }
            try {
                runParallelBatch(
                    items = targets,
                    workerCount = ArchiveDispatchers.parallelSweepConcurrency,
                    dispatcher = ArchiveDispatchers.parallelSweepDispatcher
                ) { name ->
                    if (powerThermalManager?.isThrottled == true) return@runParallelBatch

                    // Cooperative yield while foreground requests (Coil thumbnails / viewer) are in
                    // flight, so interactive decryption gets the CPU instead of this background sweep.
                    while (isSweepPaused && kotlinx.coroutines.currentCoroutineContext().isActive) {
                        kotlinx.coroutines.delay(80)
                    }
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@runParallelBatch

                    var completedThisItem = false
                    runCatchingUnlessCancelled {
                        if (file.isDirectory) {
                            // 文件夹图包：条目就是磁盘上的真实文件，直接降采样，无需任何解压路径。
                            // 缺失条目同样计入进度（与收尾推满一致：分母为整包条目数）。
                            val entryFile = File(file, name.trimStart('/', '\\'))
                            if (entryFile.isFile && entryFile.length() > 0L) {
                                thumbnailDiskCache.getOrPut(file, name, targetSizePx, password) {
                                    java.io.FileInputStream(entryFile)
                                }
                            }
                        } else {
                            when (val route = ZipSweepEntryRouter.route(nativeSession, name)) {
                                is ZipSweepRoute.NativeDirectBuffer -> {
                                    val buffer = nativeSession?.extractToDirectBuffer(route.fileIndex)
                                    if (buffer != null) {
                                        // mmap 零拷贝直通：解码直接消费缓冲，租约不跨条目持有
                                        thumbnailDiskCache
                                            .getOrPutResultFromByteBuffer(
                                                zipFile = file,
                                                entryName = name,
                                                targetSizePx = targetSizePx,
                                                password = password,
                                                buffer = buffer
                                            )
                                            .also { it.lease?.close() }
                                    } else {
                                        thumbnailDiskCache.getOrPut(file, name, targetSizePx, password) {
                                            zipArchiveManager.getEntryInputStream(file, name, password)
                                        }
                                    }
                                }
                                // 普通 ZIP（DEFLATE/加密/引擎不可用）：走池化 java.util.zip 句柄——
                                // 中央目录只解析一次、条目名哈希查表，且条目流可安全并行。
                                ZipSweepRoute.PooledStream -> thumbnailDiskCache.getOrPut(
                                    file, name, targetSizePx, password
                                ) {
                                    zipArchiveManager.getEntryInputStream(file, name, password)
                                }
                            }
                        }
                        val done = completed.incrementAndGet()
                        onProgress?.invoke(cachedBase + done, total)
                        completedThisItem = true
                    }
                    kotlinx.coroutines.yield()
                    // 全局热节流节奏：每完成 BATCH_COOLING_INTERVAL 个条目小憩一次，
                    // 避免并行worker持续满载导致 SoC 热节流。
                    if (completedThisItem && completed.get() % BATCH_COOLING_INTERVAL == 0) {
                        kotlinx.coroutines.delay(BATCH_COOLING_DELAY_MS)
                    }
                }
                // 缺失/失败条目不计 completed：扫描自然跑完（未取消、未热节流）却没到
                // 分母时补推满进度，否则进度芯片永远停在 N/M。全部计入时不补发，
                // 维持「每个计入条目恰好一次回调」的既有契约。
                if (completed.get() < total &&
                    kotlinx.coroutines.currentCoroutineContext().isActive &&
                    powerThermalManager?.isThrottled != true
                ) {
                    onProgress?.invoke(total, total)
                }
            } finally {
                // runParallelBatch 返回时全部 worker 已 join，直通缓冲均消费完毕，此刻关闭安全。
                nativeSession?.close()
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
     *
     * 逐条目持锁而非一次性持锁到底：翻页触发的交互落盘（[extractHighPriority]）到达时，
     * 预取最多占用一个条目的解压时间，且在条目之间主动让路，避免"翻页要等后台预取跑完"。
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

        val is7z = ZipArchiveManager.isSevenZFile(file)
        for (name in uncached) {
            if (awaitPrefetchIdle(interactiveRequestCount, PREFETCH_YIELD_POLL_MS, PREFETCH_YIELD_TIMEOUT_MS)) {
                return@withContext
            }
            if (archiveDiskCache.get(file, name, password) != null) continue

            val mutex = getLockFor(file)
            mutex.withLock {
                if (archiveDiskCache.get(file, name, password) != null) return@withLock
                runCatchingUnlessCancelled {
                    if (is7z) {
                        // Reuse active warm SevenZSessionManager to avoid repeating 524k-round PBKDF2 key derivations
                        sevenZSessionManager.extractSequential(file, name, password, lookahead = 0) { extractedName, stream ->
                            archiveDiskCache.getOrPut(file, extractedName, password) { stream }
                        }
                    } else {
                        archiveDiskCache.getOrPut(file, name, password) {
                            zipArchiveManager.getEntryInputStream(file, name, password)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 预取让路：前台交互落盘请求在途时按 [pollMs] 轮询等待，清空后放行。
 * 返回 true 表示应放弃本次剩余预取（协程已取消或等待超过 [timeoutMs]），
 * 交由下一次翻页触发的预取重试。
 */
internal suspend fun awaitPrefetchIdle(
    interactiveRequestCount: java.util.concurrent.atomic.AtomicInteger,
    pollMs: Long,
    timeoutMs: Long
): Boolean {
    var waitedMs = 0L
    while (interactiveRequestCount.get() > 0) {
        if (!currentCoroutineContext().isActive) return true
        if (waitedMs >= timeoutMs) return true
        kotlinx.coroutines.delay(pollMs)
        waitedMs += pollMs
    }
    return !currentCoroutineContext().isActive
}
