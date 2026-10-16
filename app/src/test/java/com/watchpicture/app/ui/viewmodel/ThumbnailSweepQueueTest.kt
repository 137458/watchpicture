package com.watchpicture.app.ui.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class ThumbnailSweepQueueTest {

    @Test
    fun `首次触发立即执行且只执行一次`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val runs = AtomicInteger(0)
        val queue = ThumbnailSweepQueue()

        queue.trigger(file = "a.zip") {
            runs.incrementAndGet()
            started.complete(Unit)
        }
        withTimeout(5_000) { started.await() }
        delay(60)
        assertEquals(1, runs.get())
    }

    @Test
    fun `运行期间的重复触发合并为一次续跑而不是并行`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val runs = AtomicInteger(0)
        val queue = ThumbnailSweepQueue()

        queue.trigger(file = "a.zip") {
            val now = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, now) }
            if (runs.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                gate.await()
            }
            concurrent.decrementAndGet()
        }
        withTimeout(5_000) { firstStarted.await() }

        // 扫描执行中反复触发：只应记录一次续跑请求，不得并发启动第二个扫描
        repeat(5) { queue.trigger(file = "a.zip") { } }
        delay(60)
        assertEquals(1, maxConcurrent.get())

        gate.complete(Unit)
        val deadline = System.currentTimeMillis() + 5_000
        while (runs.get() < 2 && System.currentTimeMillis() < deadline) delay(20)
        assertEquals("运行期间的重复触发应合并为一次续跑", 2, runs.get())
        assertEquals(1, maxConcurrent.get())
    }

    @Test
    fun `空闲时的触发会再次执行`() = runBlocking {
        val queue = ThumbnailSweepQueue()
        val firstDone = CompletableDeferred<Unit>()
        queue.trigger(file = "a.zip") { firstDone.complete(Unit) }
        withTimeout(5_000) { firstDone.await() }
        delay(60)

        val secondDone = CompletableDeferred<Unit>()
        queue.trigger(file = "a.zip") { secondDone.complete(Unit) }
        withTimeout(5_000) { secondDone.await() }
    }

    @Test
    fun `clear 后停止续跑并且可以重新触发`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val runs = AtomicInteger(0)
        val queue = ThumbnailSweepQueue()

        queue.trigger(file = "a.zip") {
            if (runs.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                gate.await()
            }
        }
        withTimeout(5_000) { firstStarted.await() }
        queue.trigger(file = "a.zip") { }
        queue.clear()

        gate.complete(Unit)
        delay(150)
        assertEquals("clear 之后不得再续跑", 1, runs.get())

        val restarted = CompletableDeferred<Unit>()
        queue.trigger(file = "a.zip") { if (runs.incrementAndGet() >= 2) restarted.complete(Unit) }
        withTimeout(5_000) { restarted.await() }
    }

    @Test
    fun `扫描抛异常不破坏队列且后续仍可触发`() = runBlocking {
        val queue = ThumbnailSweepQueue()
        queue.trigger(file = "boom.zip") { error("扫描失败") }
        delay(120)

        val recovered = CompletableDeferred<Unit>()
        queue.trigger(file = "ok.zip") { recovered.complete(Unit) }
        withTimeout(5_000) { recovered.await() }
    }

    @Test
    fun `同一文件的并发触发不会并发执行`() = runBlocking {
        val queue = ThumbnailSweepQueue()
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val jobs = (0 until 8).map {
            async {
                queue.trigger(file = "a.zip") {
                    val now = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { prev -> maxOf(prev, now) }
                    synchronized(seen) { seen.add(1) }
                    delay(20)
                    concurrent.decrementAndGet()
                }
                delay(5)
            }
        }
        jobs.forEach { it.await() }
        delay(250)

        assertTrue("应至少执行一次", seen.isNotEmpty())
        assertEquals("同一文件不得并发扫描", 1, maxConcurrent.get())
        assertFalse(seen.size > 1 && maxConcurrent.get() > 1)
    }
}
