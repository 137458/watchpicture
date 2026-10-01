package com.watchpicture.app.archive

import android.os.Process
import com.watchpicture.app.util.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated dispatchers for CPU-intensive archive operations.
 *
 * Differentiates between interactive foreground decompression (scheduled on performance
 * cores for instantaneous image viewing and AES-256 decryption) and background sweeps
 * (scheduled on energy-efficient LITTLE cores to avoid frame drops).
 */
object ArchiveDispatchers {

    private val threadId = AtomicInteger(1)
    private val sweepThreadId = AtomicInteger(1)

    private val foregroundThreadFactory = ThreadFactory { runnable ->
        Thread({
            try {
                // Interactive foreground priority allows EAS to schedule onto Cortex-X prime / Cortex-A7xx cores
                Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            } catch (_: Throwable) {
                // Ignore in non-Android JVM environments
            }
            runnable.run()
        }, "watchpic-decompress-${threadId.getAndIncrement()}")
    }

    private val backgroundThreadFactory = ThreadFactory { runnable ->
        Thread({
            try {
                // Low priority forces background batch sweeping onto energy-efficient LITTLE cores
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: Throwable) {
                // Ignore in non-Android JVM environments
            }
            runnable.run()
        }, "watchpic-sweep-${sweepThreadId.getAndIncrement()}")
    }

    /**
     * Interactive dispatcher for active image decompression, AES-256 decryption and downsampling.
     * Concurrency is dynamically sized to available device cores (3..6) on performance clusters.
     */
    val decompressDispatcher: CoroutineDispatcher = Executors
        .newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(3, 6),
            foregroundThreadFactory
        )
        .asCoroutineDispatcher()

    /**
     * Low-priority dispatcher for idle batch sweeps and lookahead preheating.
     */
    val backgroundSweepDispatcher: CoroutineDispatcher = Executors
        .newFixedThreadPool(2, backgroundThreadFactory)
        .asCoroutineDispatcher()

    /**
     * 并行批量提取线程池（调研 P1 项）：独立（非固实）ZIP 条目的批量扫描并行化。
     * 后台优先级维持 EAS 调度在能效核集群；并行度封顶 4 线程，给前台交互的
     * [decompressDispatcher]（3..6 前台优先级线程）让出大核。
     */
    val parallelSweepConcurrency: Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    val parallelSweepDispatcher: CoroutineDispatcher = Executors
        .newFixedThreadPool(parallelSweepConcurrency, backgroundThreadFactory)
        .asCoroutineDispatcher()

    /**
     * Fire-and-forget 后台维护作用域（会话关闭、缓存清理、预取预热等）：SupervisorJob
     * 隔离失败子协程，CoroutineExceptionHandler 兜底记录，避免异常走默认未捕获处理器
     * 直接杀死进程。使用时通过 launch(dispatcher) 选择具体执行线程。
     */
    val maintenanceScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
            AppLog.e("ArchiveMaint", "maintenance task failed: ${throwable.message}", throwable)
        }
    )
}
