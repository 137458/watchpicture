package com.watchpicture.app.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafScanIsolationTest {

    @Test
    fun `a single failing child is dropped instead of aborting the whole scan`() = runTest {
        val children = listOf("good_a", "broken", "good_b")

        val scanned = children.mapIsolated(tag = "SafScanTest", describe = { it }) { name ->
            if (name == "broken") throw IllegalStateException("unreadable archive")
            name
        }

        assertEquals(listOf("good_a", "good_b"), scanned)
    }

    @Test
    fun `children that resolve to null are filtered out while order is preserved`() = runTest {
        val scanned = listOf(1, 2, 3, 4, 5).mapIsolated(tag = "SafScanTest", describe = { "item$it" }) { value ->
            if (value % 2 == 1) "item$value" else null
        }

        assertEquals(listOf("item1", "item3", "item5"), scanned)
    }

    @Test
    fun `cancellation is rethrown instead of being swallowed as a child failure`() = runTest {
        var caught: Throwable? = null
        try {
            listOf(1).mapIsolated<Int, String>(tag = "SafScanTest", describe = { it.toString() }) {
                throw CancellationException("scan cancelled")
            }
        } catch (e: CancellationException) {
            caught = e
        }

        assertTrue("协程取消必须继续向上传播，不能被失败隔离当成普通异常吞掉", caught is CancellationException)
    }
}
