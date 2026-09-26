package com.watchpicture.app.archive

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded-parallel batch processing for independent items (调研 P1 项：条目级并行提取).
 *
 * Items are handed out from a shared atomic cursor, so a straggler item never blocks
 * faster workers. Structured concurrency: cancellation of the caller stops item
 * dispatch immediately and cancels in-flight [body] suspensions. A throwing [body]
 * fails the whole batch (callers must isolate failures themselves, e.g. runCatching),
 * matching [kotlinx.coroutines.coroutineScope] semantics.
 */
internal suspend fun <T> runParallelBatch(
    items: List<T>,
    workerCount: Int,
    dispatcher: CoroutineDispatcher,
    body: suspend (item: T) -> Unit
) {
    if (items.isEmpty()) return
    val workers = workerCount.coerceIn(1, items.size)
    if (workers == 1) {
        for (item in items) {
            body(item)
        }
        return
    }

    val cursor = AtomicInteger(0)
    coroutineScope {
        repeat(workers) {
            launch(dispatcher) {
                while (isActive) {
                    val index = cursor.getAndIncrement()
                    if (index >= items.size) break
                    body(items[index])
                }
            }
        }
    }
}
