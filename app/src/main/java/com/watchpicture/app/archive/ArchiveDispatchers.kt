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
 * Enforces [Process.THREAD_PRIORITY_BACKGROUND] on worker threads to guarantee
 * that heavy decompression (Deflate / LZMA) and image sampling run exclusively
 * on Linux energy-efficient (LITTLE) CPU cores. This leaves prime and performance
 * cores unhindered to fulfill the 8.3ms frame rendering budget on 120Hz mobile screens.
 */
object ArchiveDispatchers {

    private val threadId = AtomicInteger(1)

    private val backgroundThreadFactory = ThreadFactory { runnable ->
        Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: Throwable) {
                // Ignore in non-Android JVM environments
            }
            runnable.run()
        }, "watchpic-decompress-${threadId.getAndIncrement()}")
    }

    /**
     * Dedicated dispatcher for archive decompression and downsampling.
     * Concurrency is fixed at 3 to match mobile little-core clusters (typically 3~4 cores).
     */
    val decompressDispatcher: CoroutineDispatcher = Executors
        .newFixedThreadPool(3, backgroundThreadFactory)
        .asCoroutineDispatcher()
}
