package com.watchpicture.app.archive

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
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
                // Interactive foreground priority allows EAS to use fast performance / middle cores
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
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
}
