package com.watchpicture.app.archive

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 缩略图落盘编码策略：无 alpha 一律 JPEG（5~12ms），避免 WEBP 软编 35~60ms 阻塞扫描吞吐。
 * 带 alpha 才回落 WEBP 保透明。
 */
class ThumbnailEncodeFormatTest {

    @Test
    fun `无 alpha 图像编码为 JPEG`() {
        assertEquals(
            Bitmap.CompressFormat.JPEG,
            ThumbnailEncodeFormat.resolve(mime = "image/jpeg", hasAlpha = false, sdkInt = 30)
        )
        assertEquals(
            Bitmap.CompressFormat.JPEG,
            ThumbnailEncodeFormat.resolve(mime = "image/png", hasAlpha = false, sdkInt = 30)
        )
    }

    @Test
    fun `带 alpha 图像回落 WEBP 以保留透明通道`() {
        assertEquals(
            Bitmap.CompressFormat.WEBP_LOSSY,
            ThumbnailEncodeFormat.resolve(mime = "image/png", hasAlpha = true, sdkInt = 30)
        )
        assertEquals(
            Bitmap.CompressFormat.WEBP_LOSSY,
            ThumbnailEncodeFormat.resolve(mime = "image/webp", hasAlpha = true, sdkInt = 33)
        )
    }

    @Test
    fun `低版本系统带 alpha 回落旧版 WEBP`() {
        @Suppress("DEPRECATION")
        val expected = Bitmap.CompressFormat.WEBP
        assertEquals(
            expected,
            ThumbnailEncodeFormat.resolve(mime = "image/png", hasAlpha = true, sdkInt = 29)
        )
    }

    @Test
    fun `质量参数固定 75 与历史行为一致`() {
        assertEquals(75, ThumbnailEncodeFormat.QUALITY)
    }
}
