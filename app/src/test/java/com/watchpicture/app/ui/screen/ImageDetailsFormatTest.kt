package com.watchpicture.app.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 图片详情弹窗文件大小格式化的行为锁定:KB/MB 进位、小数位、0 与负数字节的
 * 流式兜底(null)。提取自 ImageDetailsDialog,行为与原内联实现一致。
 */
class ImageDetailsFormatTest {

    @Test
    fun `formats bytes below 1MB as KB with one decimal`() {
        // 原内联行为:1 字节即进 KB 档,%.1f 舍入为 0.0
        assertEquals("0.0 KB", formatImageFileSize(1L))
        assertEquals("1.0 KB", formatImageFileSize(1024L))
        assertEquals("1023.4 KB", formatImageFileSize((1023.4 * 1024).toLong()))
    }

    @Test
    fun `formats sizes at or above 1MB as MB with two decimals`() {
        assertEquals("1.00 MB", formatImageFileSize(1024L * 1024))
        assertEquals("1.50 MB", formatImageFileSize((1.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `zero and negative bytes fall back to streaming placeholder`() {
        assertNull(formatImageFileSize(0L))
        assertNull(formatImageFileSize(-5L))
    }
}
