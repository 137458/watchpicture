package com.watchpicture.app.ui.screen

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchpicture.app.R
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.navigation.AppRoute
import com.watchpicture.app.ui.component.UpdateDialog
import com.watchpicture.app.ui.component.bottombar.FloatingBottomBar
import com.watchpicture.app.ui.component.bottombar.FloatingBottomBarMode
import com.watchpicture.app.ui.viewmodel.PackViewModel
import com.watchpicture.app.update.UpdateCheckResult
import com.watchpicture.app.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主页面框架：承载 HorizontalPager（图包 / 设置）、宽屏侧边导航栏与 Miuix 液态玻璃悬浮底栏。
 */
@Composable
fun MainScreen(
    onNavigate: (AppRoute) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefsRepo = WatchPictureApp.instance.preferencesRepository
    val packViewModel: PackViewModel = viewModel()
    val updateManager = remember { UpdateManager(context) }

    val autoCheckUpdate by prefsRepo.autoCheckUpdateFlow.collectAsStateWithLifecycle(initialValue = true)
    val ignoredVersion by prefsRepo.ignoredVersionFlow.collectAsStateWithLifecycle(initialValue = null)
    val wideScreenRail by prefsRepo.wideScreenRailFlow.collectAsStateWithLifecycle(initialValue = true)

    val configuration = LocalConfiguration.current
    val useNavigationRail = configuration.screenWidthDp >= 600 && wideScreenRail

    var availableUpdate by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    // 自动在后台静默检查更新
    LaunchedEffect(autoCheckUpdate) {
        if (!autoCheckUpdate) return@LaunchedEffect
        coroutineScope.launch(Dispatchers.IO) {
            val result = updateManager.checkForUpdate()
            result.onSuccess { info ->
                if (info.hasUpdate && info.latestVersion != ignoredVersion) {
                    availableUpdate = info
                    showUpdateDialog = true
                }
            }
        }
    }

    // 监听外部关联打开图集压缩包事件 (冷启动与热启动)
    LaunchedEffect(Unit) {
        WatchPictureApp.instance.externalArchiveFlow.collect { uri ->
            packViewModel.openSingleArchive(uri, onNavigate)
        }
    }

    val tabPacksTitle = stringResource(R.string.tab_packs)
    val tabSettingsTitle = stringResource(R.string.tab_settings)

    val navigationItems = remember(tabPacksTitle, tabSettingsTitle) {
        listOf(
            NavigationItem(tabPacksTitle, Icons.Default.Collections),
            NavigationItem(tabSettingsTitle, Icons.Default.Settings),
        )
    }

    val pagerState = rememberPagerState(pageCount = { navigationItems.size })
    val navBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val powerThermalManager = WatchPictureApp.instance.powerThermalManager
    val throttleLevel by powerThermalManager.throttleLevel.collectAsStateWithLifecycle()
    val isThrottled = throttleLevel == com.watchpicture.app.archive.PowerThermalManager.ThrottleLevel.THROTTLED

    val shaderCapable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isRuntimeShaderSupported()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val floatingBackdrop = if (shaderCapable) {
        rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    } else null

    val bottomInset = if (useNavigationRail) navBarBottomPadding else 76.dp + navBarBottomPadding
    val tabContentPadding = PaddingValues(bottom = bottomInset)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (useNavigationRail) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    defaultWindowInsetsPadding = true,
                ) {
                    navigationItems.forEachIndexed { index, item ->
                        NavigationRailItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            icon = item.icon,
                            label = item.label,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // 页面内容采样层
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (floatingBackdrop != null) Modifier.layerBackdrop(floatingBackdrop) else Modifier
                        )
                ) { page ->
                    when (page) {
                        0 -> PackListScreen(
                            viewModel = packViewModel,
                            onNavigate = onNavigate,
                            contentPadding = tabContentPadding,
                        )
                        1 -> SettingsScreen(
                            contentPadding = tabContentPadding,
                            onNavigateToUpdate = { onNavigate(AppRoute.Update) },
                        )
                    }
                }

                // 液态玻璃悬浮底栏
                if (!useNavigationRail) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp + navBarBottomPadding)
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        FloatingBottomBar(
                            items = navigationItems,
                            selectedIndex = { pagerState.currentPage },
                            onSelected = { index ->
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            backdrop = floatingBackdrop,
                            mode = when {
                                !shaderCapable -> FloatingBottomBarMode.Blur
                                isThrottled -> FloatingBottomBarMode.Blur
                                else -> FloatingBottomBarMode.LiquidGlass
                            },
                        )
                    }
                }
            }
        }
    }

    if (showUpdateDialog && availableUpdate != null) {
        UpdateDialog(
            show = showUpdateDialog,
            releaseInfo = availableUpdate!!,
            onDismiss = { showUpdateDialog = false },
            onUpdate = { url ->
                updateManager.openInBrowser(context, url)
            },
            onIgnore = { ver ->
                coroutineScope.launch {
                    prefsRepo.saveIgnoredVersion(ver)
                }
            },
            externalScope = coroutineScope,
        )
    }
}
