package com.watchpicture.app.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrowsePositionMemoryTest {

    @Before
    fun setUp() {
        ViewerViewModel.clearBrowseStateForTest()
    }

    @Test
    fun `saves and restores scroll position and lastViewedIndex isolated per packId`() {
        val packA = "/storage/emulated/0/Pictures/PackA.zip"
        val packB = "/storage/emulated/0/Pictures/PackB.zip"

        // Default unvisited state
        assertEquals(PackBrowseState(0, 0, -1), ViewerViewModel.getBrowseState(packA))

        // Save grid scroll offset for Pack A
        ViewerViewModel.saveScrollPosition(packA, firstVisibleItemIndex = 12, firstVisibleItemScrollOffset = 84)
        assertEquals(PackBrowseState(12, 84, -1), ViewerViewModel.getBrowseState(packA))

        // User clicks image #15 in Pack A and swipes to image #27 in GalleryViewer
        ViewerViewModel.saveLastViewedIndex(packA, 15)
        ViewerViewModel.saveLastViewedIndex(packA, 27)
        assertEquals(PackBrowseState(12, 84, 27), ViewerViewModel.getBrowseState(packA))

        // Pack B state remains independent
        ViewerViewModel.saveScrollPosition(packB, firstVisibleItemIndex = 6, firstVisibleItemScrollOffset = 40)
        ViewerViewModel.saveLastViewedIndex(packB, 8)
        assertEquals(PackBrowseState(6, 40, 8), ViewerViewModel.getBrowseState(packB))
        assertEquals(PackBrowseState(12, 84, 27), ViewerViewModel.getBrowseState(packA))
    }

    @Test
    fun `shouldScrollToLastViewed preserves viewport when visible and scrolls when paged outside viewport`() {
        val visibleViewport = listOf(12, 13, 14, 15, 16, 17, 18, 19, 20)

        // Case 1: User clicked image #15 and exited without paging out of viewport -> keep exact pixel offset
        assertFalse(
            ViewerViewModel.shouldScrollToLastViewed(
                lastViewedIndex = 15,
                visibleItemIndices = visibleViewport,
                totalItems = 100
            )
        )

        // Case 2: User swiped in fullscreen viewer to image #42 (outside visible 12..20) -> scroll grid to #42
        assertTrue(
            ViewerViewModel.shouldScrollToLastViewed(
                lastViewedIndex = 42,
                visibleItemIndices = visibleViewport,
                totalItems = 100
            )
        )

        // Case 3: Unvisited (-1) or out-of-range index -> do not scroll
        assertFalse(
            ViewerViewModel.shouldScrollToLastViewed(
                lastViewedIndex = -1,
                visibleItemIndices = visibleViewport,
                totalItems = 100
            )
        )
        assertFalse(
            ViewerViewModel.shouldScrollToLastViewed(
                lastViewedIndex = 100,
                visibleItemIndices = visibleViewport,
                totalItems = 100
            )
        )
    }
}
