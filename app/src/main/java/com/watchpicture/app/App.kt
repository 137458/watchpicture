package com.watchpicture.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchpicture.app.navigation.AppRoute
import com.watchpicture.app.ui.screen.GalleryViewerScreen
import com.watchpicture.app.ui.screen.MainScreen
import com.watchpicture.app.ui.screen.ThumbnailGridScreen
import com.watchpicture.app.ui.screen.ThemeSettingsScreen
import com.watchpicture.app.ui.screen.UpdateScreen
import com.watchpicture.app.ui.theme.WatchPictureTheme
import com.watchpicture.app.ui.viewmodel.ViewerViewModel
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions

/**
 * Root Composable assembling WatchPictureTheme and NavDisplay route transitions.
 */
@Composable
fun App() {
    val prefsRepo = WatchPictureApp.instance.preferencesRepository
    val darkMode by prefsRepo.darkModeFlow.collectAsStateWithLifecycle(initialValue = 0)
    val colorMode by prefsRepo.colorModeFlow.collectAsStateWithLifecycle(initialValue = 1)
    val seedColor by prefsRepo.seedColorFlow.collectAsStateWithLifecycle(initialValue = 0xFF2196F3L)
    val amoledDark by prefsRepo.amoledDarkFlow.collectAsStateWithLifecycle(initialValue = false)
    val paletteStyleIndex by prefsRepo.paletteStyleIndexFlow.collectAsStateWithLifecycle(initialValue = 0)
    val useSpec2025 by prefsRepo.useSpec2025Flow.collectAsStateWithLifecycle(initialValue = false)

    WatchPictureTheme(
        darkMode = darkMode,
        colorMode = colorMode,
        seedColor = seedColor,
        amoledDark = amoledDark,
        paletteStyleIndex = paletteStyleIndex,
        useSpec2025 = useSpec2025,
    ) {
        val backStack = rememberNavBackStack<AppRoute>(AppRoute.Main)

        // miuix-nav requires every entry on the stack to have a deterministic and unique contentKey.
        // Using a stable contentKey per route identity preserves SaveableStateHolder (LazyGridState)
        // and ViewModelStoreOwner when pushing GalleryViewer and popping back to ThumbnailGrid.
        val navigate: (AppRoute) -> Unit = { route ->
            if (backStack.lastOrNull() != route) {
                backStack.removeAll { existing ->
                    existing == route ||
                        (existing is AppRoute.ThumbnailGrid && route is AppRoute.ThumbnailGrid && existing.packId == route.packId) ||
                        (existing is AppRoute.GalleryViewer && route is AppRoute.GalleryViewer && existing.packId == route.packId)
                }
                backStack.add(route)
            }
        }

        BackHandler(enabled = backStack.size > 1) {
            backStack.removeLastOrNull()
        }

        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            transition = NavTransitions.MiuixDefault,
            modifier = Modifier.fillMaxSize()
        ) {
            entry<AppRoute.Main> {
                MainScreen(
                    onNavigate = navigate
                )
            }

            entry<AppRoute.Update>(
                swipeDismiss = NavSwipeDirection.LeftToRight,
            ) {
                UpdateScreen(
                    onBack = { backStack.removeLastOrNull() }
                )
            }

            entry<AppRoute.ThemeSettings>(
                swipeDismiss = NavSwipeDirection.LeftToRight,
            ) {
                ThemeSettingsScreen(
                    onBack = { backStack.removeLastOrNull() }
                )
            }

            entry<AppRoute.ThumbnailGrid>(
                contentKey = { route -> "grid#${route.packId}" },
                swipeDismiss = NavSwipeDirection.LeftToRight,
            ) { route ->
                val viewerViewModel: ViewerViewModel = viewModel()
                ThumbnailGridScreen(
                    packId = route.packId,
                    title = route.title,
                    viewModel = viewerViewModel,
                    onBack = { backStack.removeLastOrNull() },
                    onNavigate = navigate
                )
            }

            entry<AppRoute.GalleryViewer>(
                contentKey = { route -> "viewer#${route.packId}" }
            ) { route ->
                val viewerViewModel: ViewerViewModel = viewModel()
                GalleryViewerScreen(
                    packId = route.packId,
                    initialIndex = route.initialIndex,
                    viewModel = viewerViewModel,
                    onBack = { backStack.removeLastOrNull() }
                )
            }
        }
    }
}
