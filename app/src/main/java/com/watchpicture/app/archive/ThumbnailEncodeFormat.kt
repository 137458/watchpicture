package com.watchpicture.app.archive

import android.graphics.Bitmap
import android.os.Build

/**
 * 缩略图落盘编码策略。
 *
 * 无 alpha 通道一律 JPEG：软编约 5~12ms，明显快于 WEBP 软编的 35~60ms，
 * 是缩略图扫描吞吐的主要瓶颈之一。仅当源图确实带 alpha 时才回落 WEBP 以保留透明通道。
 */
internal object ThumbnailEncodeFormat {

    const val QUALITY = 75

    /**
     * @param mime 源图 MIME 类型（保留用于诊断与后续策略扩展，当前不参与判定）
     * @param hasAlpha 源图是否带 alpha 通道
     * @param sdkInt 运行 API 级别（低于 30 时没有 WEBP_LOSSY，回落旧版 WEBP）
     */
    @Suppress("NewApi")
    fun resolve(
        mime: String,
        hasAlpha: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Bitmap.CompressFormat = if (hasAlpha) {
        if (sdkInt >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
    } else {
        Bitmap.CompressFormat.JPEG
    }
}
