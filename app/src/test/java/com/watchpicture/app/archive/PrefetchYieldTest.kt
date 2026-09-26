package com.watchpicture.app.archive

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PrefetchYieldTest {

    @Test
    fun `returns false immediately when no interactive request is in flight`() = runTest {
        assertFalse(awaitPrefetchIdle(AtomicInteger(0), pollMs = 60, timeoutMs = 1_500))
        assertEquals(0, currentTime)
    }

    @Test
    fun `returns false after the interactive request clears`() = runTest {
        val count = AtomicInteger(1)
        launch {
            delay(180)
            count.decrementAndGet()
        }
        assertFalse(awaitPrefetchIdle(count, pollMs = 60, timeoutMs = 1_500))
        // 让路等待在交互请求清空后立刻结束，未触及超时
        assertTrue(currentTime < 1_500)
    }

    @Test
    fun `aborts exactly at the timeout while requests persist`() = runTest {
        assertTrue(awaitPrefetchIdle(AtomicInteger(1), pollMs = 60, timeoutMs = 1_500))
        assertEquals(1_500, currentTime)
    }

    @Test
    fun `cancelled caller aborts the wait without running to timeout`() = runTest {
        val count = AtomicInteger(1)
        var result: Boolean? = null
        val job = launch { result = awaitPrefetchIdle(count, pollMs = 60, timeoutMs = 1_500) }
        advanceTimeBy(120)
        runCurrent()
        job.cancelAndJoin()
        // 等待随取消终止：既未返回超时结果，也未放行预取
        assertNull(result)
        assertTrue(currentTime < 1_500)
    }
}
