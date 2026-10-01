package com.watchpicture.app.archive

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * runCatchingUnlessCancelled 的语义锁定:业务失败吞为 Result.failure,
 * 取消异常必须放行传播(runCatching 会吞掉它,令取消后的提取协程继续
 * 跑全档回退白烧 CPU)。
 */
class CoordinatorCancellationGuardTest {

    @Test
    fun `business failure is swallowed as failure result`() {
        val result = runCatchingUnlessCancelled<String> { throw IllegalStateException("boom") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `success passes through`() {
        assertEquals("ok", runCatchingUnlessCancelled { "ok" }.getOrNull())
    }

    @Test
    fun `cancellation propagates instead of being swallowed`() {
        var thrown: Throwable? = null
        try {
            runCatchingUnlessCancelled<Unit> { throw CancellationException("cancelled") }
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("cancellation must rethrow", thrown is CancellationException)
    }
}
