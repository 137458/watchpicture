package com.watchpicture.app.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * VideoLauncher 播放副本缓存名的行为锁定:
 * - 相同输入必须稳定同名(缓存命中);
 * - 归档文件 lastModified 变化必须换名(归档被替换后不得再播旧副本);
 * - 不同条目不得互撞。
 */
class VideoLauncherCacheNameTest {

    private val displayName = "第01话.mp4"

    @Test
    fun `same inputs produce stable cache name`() {
        val a = VideoLauncher.playbackCacheName("pack-1", "videos/01.mp4", 1700000000000L, displayName)
        val b = VideoLauncher.playbackCacheName("pack-1", "videos/01.mp4", 1700000000000L, displayName)
        assertEquals(a, b)
    }

    @Test
    fun `replaced archive with new lastModified must rotate cache name`() {
        val before = VideoLauncher.playbackCacheName("pack-1", "videos/01.mp4", 1700000000000L, displayName)
        val after = VideoLauncher.playbackCacheName("pack-1", "videos/01.mp4", 1799999999999L, displayName)
        assertNotEquals("archive replaced must not serve stale playback copy", before, after)
    }

    @Test
    fun `different entries never collide`() {
        val a = VideoLauncher.playbackCacheName("pack-1", "videos/01.mp4", 1700000000000L, displayName)
        val b = VideoLauncher.playbackCacheName("pack-1", "videos/02.mp4", 1700000000000L, "第02话.mp4")
        assertNotEquals(a, b)
    }

    @Test
    fun `unsafe characters in display name are sanitized`() {
        val name = VideoLauncher.playbackCacheName("pack-1", "videos/01.mp4", 1700000000000L, "a/b\\c:d*e?f\"g<h>i|j.mp4")
        assertFalse("cache file name must not contain path-unsafe characters", name.contains(Regex("[\\\\/:*?\"<>|]")))
    }
}
