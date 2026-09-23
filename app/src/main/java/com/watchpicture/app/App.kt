package com.watchpicture.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchpicture.app.navigation.AppRoute
import com.watchpicture.app.ui.screen.GalleryViewerScreen
import com.watchpicture.app.ui.screen.MainScreen
import com.watchpicture.app.ui.screen.PackListScreen
import com.watchpicture.app.ui.screen.ThumbnailGridScreen
import com.watchpicture.app.ui.screen.UpdateScreen
import com.watchpicture.app.ui.viewmodel.PackViewModel
import com.watchpicture.app.ui.viewmodel.ViewerViewModel
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Root Composable assembling MiuixTheme and NavDisplay route transitions.
 */
@Composable
fun App() {
    val themeController = remember { ThemeController(ColorSchemeMode.System) }

    MiuixTheme(controller = themeController) {
        val backStack = rememberNavBackStack<AppRoute>(AppRoute.Main)

        // miuix-nav requires every entry on the stack to have a unique contentKey. Re-opening the
        // same pack (e.g. back from its grid, then tapping it again) would push a duplicate
        // contentKey and crash. Navigate by replacing any existing identical route instead.
        val navigate: (AppRoute) -> Unit = { route ->
            if (backStack.lastOrNull() != route) {
                backStack.removeAll { it == route }
                backStack.add(route)
            }
        }

        // Guarantee each grid / viewer push gets a distinct contentKey even across consecutive
        // entries of the same pack, since same-key adjacent navigations get deduplicated.
        val contentSeq = remember { java.util.concurrent.atomic.AtomicInteger() }

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

            entry<AppRoute.PackList> {
                val packViewModel: PackViewModel = viewModel()
                PackListScreen(
                    viewModel = packViewModel,
                    onNavigate = navigate
                )
            }

            entry<AppRoute.Update> {
                UpdateScreen(
                    onBack = { backStack.removeLastOrNull() }
                )
            }

            entry<AppRoute.ThumbnailGrid>(
                contentKey = { route -> "grid#${route.packId}#${contentSeq.getAndIncrement()}" }
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
                contentKey = { route -> "viewer#${route.packId}#${route.initialIndex}#${contentSeq.getAndIncrement()}" }
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
