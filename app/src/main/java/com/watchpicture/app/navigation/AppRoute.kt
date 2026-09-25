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
}
