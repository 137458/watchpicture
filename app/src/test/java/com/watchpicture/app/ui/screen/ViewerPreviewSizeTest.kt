package com.watchpicture.app.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerPreviewSizeTest {

    @Test
    fun `720p screen maps to 720 preview`() {
        assertEquals(720, viewerPreviewTargetPx(720))
    }

    @Test
    fun `1080p screen maps to 1080 preview`() {
        assertEquals(1080, viewerPreviewTargetPx(1080))
    }

    @Test
    fun `1440p screen maps to 1440 preview`() {
        assertEquals(1440, viewerPreviewTargetPx(1440))
    }

    @Test
    fun `small screen below 720 still requests 720 preview`() {
        assertEquals(720, viewerPreviewTargetPx(640))
    }

    @Test
    fun `oversized screen is capped at 1440 preview`() {
        assertEquals(1440, viewerPreviewTargetPx(2160))
    }

    @Test
    fun `screen between 1080 and 1440 maps to 1440 preview`() {
        assertEquals(1440, viewerPreviewTargetPx(1200))
    }
}