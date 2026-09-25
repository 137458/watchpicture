package com.watchpicture.app.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 视频播放进度时间格式化的边界校验：秒级截断、分秒补零、跨小时切换、负数与未知时长兜底。
 */
class VideoPlayerTimeFormatTest {

    @Test
    fun `formats sub-minute durations with zero padding`() {
        assertEquals("00:00", formatPlaybackTime(0L))
        assertEquals("00:01", formatPlaybackTime(1_000L))
        assertEquals("00:59", formatPlaybackTime(59_999L))
    }

    @Test
    fun `formats minute durations without hour segment`() {
        assertEquals("01:00", formatPlaybackTime(60_000L))
        assertEquals("59:59", formatPlaybackTime(3_599_000L))
    }

    @Test
    fun `switches to hour format once duration reaches one hour`() {
        assertEquals("1:00:00", formatPlaybackTime(3_600_000L))
        assertEquals("1:01:01", formatPlaybackTime(3_661_000L))
        assertEquals("12:34:56", formatPlaybackTime(45_296_000L))
    }

    @Test
    fun `clamps negative position to zero`() {
        assertEquals("00:00", formatPlaybackTime(-1L))
        assertEquals("00:00", formatPlaybackTime(Long.MIN_VALUE))
    }
}
