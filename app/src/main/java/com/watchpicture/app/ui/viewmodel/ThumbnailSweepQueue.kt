package com.watchpicture.app.ui.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 缩略图全量扫描的串行队列。
 *
 * 缩略图扫描不再因滚动暂停，触发频率很高（列表加载、尺寸变化、重进页面）。而
 * [com.watchpicture.app.archive.ArchiveExtractionCoordinator.startBatchThumbnailSweep]
 * 只保留一个 activeSweepJob，重复并发调用会互相取消、令扫描反复重启。
 *
 * 本队列保证同一时刻只有一个扫描在跑；运行期间的重复触发被合并为「跑完再来一轮」，
 * 续跑复用首次提交的 block（block 自身应重新读取最新数据），因此一次扫描最多排一个后继。
 * [clear] 用于页面销毁：丢弃待跑请求并取消正在运行的扫描。
 */
class ThumbnailSweepQueue {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ ->
            // 扫描失败不致命：队列自身已吞掉单次异常，这里只是兜底避免走默认未捕获处理器。
        }
    )

    private val lock = Any()
    private var running = false
    private var pending = false
    private var currentJob: Job? = null

    /** 最近一次扫描的目标文件，仅用于诊断。 */
    @Volatile
    var lastFile: String? = null
        private set

    /**
     * 请求执行一次扫描。空闲时立即启动，运行中则记录一次续跑请求（不会并发启动第二个扫描）。
     */
    fun trigger(file: String, block: suspend () -> Unit) {
        val startNow: Boolean
        synchronized(lock) {
            if (running) {
                pending = true
                startNow = false
            } else {
                running = true
                startNow = true
            }
        }
        if (startNow) launchDrain(file, block)
    }

    /** 丢弃待跑请求并取消正在运行的扫描。 */
    fun clear() {
        val job = synchronized(lock) {
            pending = false
            currentJob
        }
        job?.cancel()
    }

    private fun launchDrain(file: String, block: suspend () -> Unit) {
        // LAZY 启动：确保 currentJob 在协程体执行前完成登记，避免快速完成时把登记清成空档。
        val job = scope.launch(start = CoroutineStart.LAZY) {
            lastFile = file
            while (true) {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // 单次扫描失败不得污染队列：吞掉后继续处理续跑请求
                }
                val again = synchronized(lock) {
                    if (pending) {
                        pending = false
                        true
                    } else {
                        running = false
                        currentJob = null
                        false
                    }
                }
                if (!again) return@launch
            }
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (currentJob === job) {
                    currentJob = null
                    running = false
                }
            }
        }
        synchronized(lock) { currentJob = job }
        job.start()
    }
}
