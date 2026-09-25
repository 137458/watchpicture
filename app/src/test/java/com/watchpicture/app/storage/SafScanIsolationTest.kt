package com.watchpicture.app.storage

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SafScanIsolationTest {

    @Test
    fun `a single failing child is dropped instead of aborting the whole scan`() = runTest {
        val children = listOf("good_a", "broken", "good_b")

        val scanned = children.mapIsolated("SafScanTest") { name ->
            if (name == "broken") throw IllegalStateException("unreadable archive")
            name
        }

        assertEquals(listOf("good_a", "good_b"), scanned)
    }

    @Test
    fun `children that resolve to null are filtered out while order is preserved`() = runTest {
        val scanned = listOf(1, 2, 3, 4, 5).mapIsolated("SafScanTest") { value ->
            if (value % 2 == 1) "item$value" else null
        }

        assertEquals(listOf("item1", "item3", "item5"), scanned)
    }
}
