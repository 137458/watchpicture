package com.watchpicture.app.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * App navigation routes conforming to Miuix nav specifications.
 */
@Serializable
sealed interface AppRoute : NavKey {

    @Serializable
    data object Main : AppRoute

    @Serializable
    data object Update : AppRoute

    @Serializable
    data object ThemeSettings : AppRoute

    @Serializable
    data class ThumbnailGrid(val packId: String, val title: String) : AppRoute

    @Serializable
    data class GalleryViewer(val packId: String, val initialIndex: Int = 0) : AppRoute

    /**
     * 应用内视频播放页。[source] 为该条目的可播放来源（裸文件路径或 content Uri），
     * 由缩略图网格/画廊在点击视频条目时从 [com.watchpicture.app.model.PackImage] 提取。
     */
    @Serializable
    data class VideoPlayer(
        val packId: String,
        val entryPath: String,
        val displayName: String,
        val source: String
    ) : AppRoute
}
