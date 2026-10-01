package com.watchpicture.app.archive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放副本自动裁剪的保护判定:在途写入(.tmp- 唯一名)与近期落地的副本
 * 不得成为 LRU 淘汰对象,否则正在播放/正在物化的视频被裁掉。
 */
class PlaybackTrimProtectionTest {

    private val now = 1_700_000_000_000L
    private val fiveMinutes = 5L * 60 * 1000

    @Test
    fun `in-flight temp copy is always protected`() {
        // 在途写入即使 mtime 恰好新鲜度边界外,也不得裁剪
        assertTrue(
            CacheMaintenance.isProtectedFromPlaybackTrim(
                name = "abcd1234_ep01.mp4.tmp-8f3a",
                lastModified = now - fiveMinutes - 1,
                nowMs = now
            )
        )
    }

    @Test
    fun `freshly materialized copy is protected within grace window`() {
        assertTrue(
            CacheMaintenance.isProtectedFromPlaybackTrim(
                name = "abcd1234_ep01.mp4",
                lastModified = now - fiveMinutes + 1_000,
                nowMs = now
            )
        )
    }

    @Test
    fun `stale finished copy is trimmable`() {
        assertFalse(
            CacheMaintenance.isProtectedFromPlaybackTrim(
                name = "abcd1234_ep01.mp4",
                lastModified = now - fiveMinutes - 1,
                nowMs = now
            )
        )
    }
}
