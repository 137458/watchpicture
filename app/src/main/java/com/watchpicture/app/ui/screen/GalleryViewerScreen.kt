package com.watchpicture.app.ui.screen

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.bitmapConfig
import coil3.size.Precision
import com.watchpicture.app.R
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.archive.CacheFileLeases
import com.watchpicture.app.archive.VideoLauncher
import com.watchpicture.app.coil.ZipImageSource
import com.watchpicture.app.model.PackImage
import com.watchpicture.app.model.isVideo
import com.watchpicture.app.model.toImageModel
import com.watchpicture.app.navigation.AppRoute
import com.watchpicture.app.storage.ReadingMode
import com.watchpicture.app.ui.component.VideoEntryPlaceholder
import com.watchpicture.app.ui.component.bottombar.vibrancy
import com.watchpicture.app.ui.viewmodel.ViewerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import com.watchpicture.app.ui.component.SquircleShape
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Screen orientation lock state for the reader session.
 */
private enum class ReaderOrientationMode(
    val label: String,
    val activityOrientation: Int,
    val icon: ImageVector
) {
    SYSTEM("跟随系统", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, Icons.Default.ScreenRotation),
    PORTRAIT("锁定竖屏", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Icons.Default.StayCurrentPortrait),
    LANDSCAPE("锁定横屏", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, Icons.Default.StayCurrentLandscape);

    fun next(): ReaderOrientationMode = when (this) {
        SYSTEM -> PORTRAIT
        PORTRAIT -> LANDSCAPE
        LANDSCAPE -> SYSTEM
    }
}

/**
 * Reusable Miuix frosted glass surface that samples real-time image content via [Backdrop]
 * on Android 13+ (TIRAMISU) with AGSL support, or degrades gracefully to a translucent dark surface.
 */
@Composable
private fun ViewerGlassSurface(
    backdrop: Backdrop?,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val surfaceModifier = if (backdrop != null) {
        Modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    blur(22.dp.toPx(), 22.dp.toPx())
                    vibrancy()
                },
                onDrawSurface = { drawRect(Color(0x99161618)) }
            )
            .border(0.5.dp, Color.White.copy(alpha = 0.16f), shape)
    } else {
        Modifier
            .clip(shape)
            .background(Color(0xD91A1A1E))
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), shape)
    }

    Box(modifier = modifier.then(surfaceModifier)) {
        content()
    }
}

/**
 * Fullscreen picture viewer providing:
 * - Real-time Miuix liquid frosted glass HUD sampling the active picture (`viewerBackdrop`)
 * - HyperOS physical enter/exit transitions for floating top & bottom capsules
 * - HorizontalPager page flipping with dynamic conflict avoidance during zoom
 * - RTL auto-mirrored progress capsule (`ChapterNavigator`) with floating scrub preview bubble & haptics
 * - Multi-function quick reader toolbar (`BottomReaderBar`: Reading Direction, Screen Orientation, Auto-Play, Page Grid Sheet)
 */
@Composable
fun GalleryViewerScreen(
    packId: String,
    initialIndex: Int,
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    onNavigate: (AppRoute) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val readingMode by viewModel.readingMode.collectAsStateWithLifecycle(initialValue = ReadingMode.LTR)
    val passwordStore = WatchPictureApp.instance.sessionPasswordStore
    val sessionPassword = remember(packId) { passwordStore.get(packId) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current

    var isImmersive by remember { mutableStateOf(false) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }
    var sliderScrubbingPage by remember { mutableStateOf<Int?>(null) }
    var lastHapticPage by remember { mutableIntStateOf(-1) }
    var scrubJob by remember { mutableStateOf<Job?>(null) }

    var showPageSheet by remember { mutableStateOf(false) }
    var showImageInfoDialog by remember { mutableStateOf(false) }
    var isAutoPlay by remember { mutableStateOf(false) }
    var orientationMode by remember { mutableStateOf(ReaderOrientationMode.SYSTEM) }

    // Real-time background frosted glass sampling source
    val viewerBackdrop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isRuntimeShaderSupported()) {
        rememberLayerBackdrop {
            drawRect(Color.Black)
            drawContent()
        }
    } else {
        null
    }

    // Toggle system bars for immersive viewing
    val view = LocalView.current
    val activity = view.context as? Activity
    val window = activity?.window

    DisposableEffect(isImmersive) {
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            if (isImmersive) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Restore screen orientation to system default when leaving the viewer
    DisposableEffect(Unit) {
        onDispose {
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(packId) {
        if (uiState.images.isEmpty()) {
            viewModel.loadImages(packId)
        }
    }

    val cachedImages = remember(packId) { ViewerViewModel.getCachedImages(packId) }
    val images = if (uiState.images.isNotEmpty()) uiState.images else (cachedImages ?: emptyList())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if ((uiState.isLoading && images.isEmpty()) || images.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                InfiniteProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = Color.White
                )
            }
        } else {
            val safeInitialPage = initialIndex.coerceIn(0, images.size - 1)
            val pagerState = rememberPagerState(
                initialPage = safeInitialPage,
                pageCount = { images.size }
            )

            // Reset zoom state on page change & sync lastViewedIndex for thumbnail grid restoration
            LaunchedEffect(packId, pagerState.currentPage) {
                isCurrentPageZoomed = false
                ViewerViewModel.saveLastViewedIndex(packId, pagerState.currentPage)
            }

            // Auto-play slideshow timer (every 3.5 seconds, pauses while zoomed, stops at last page)
            LaunchedEffect(isAutoPlay, pagerState.currentPage, isCurrentPageZoomed, images.size) {
                if (!isAutoPlay || isCurrentPageZoomed) return@LaunchedEffect
                if (pagerState.currentPage >= images.size - 1) {
                    isAutoPlay = false
                    return@LaunchedEffect
                }
                delay(3500L)
                if (isAutoPlay && !isCurrentPageZoomed) {
                    val nextPage = pagerState.currentPage + 1
                    if (nextPage < images.size) {
                        pagerState.animateScrollToPage(nextPage)
                    } else {
                        isAutoPlay = false
                    }
                }
            }

            // Directional background prefetching of adjacent archive entries using coordinator
            val app = context.applicationContext as? WatchPictureApp
            val coordinator = app?.archiveExtractionCoordinator

            DisposableEffect(packId) {
                coordinator?.pauseBackgroundSweep()
                onDispose {
                    coordinator?.resumeBackgroundSweep()
                }
            }

            LaunchedEffect(pagerState.currentPage, readingMode, images) {
                // Debounce so fast flings skip intermediate pages
                delay(100)
                val isRtl = (readingMode == ReadingMode.RTL)
                val curr = pagerState.currentPage
                val prefetchIndices = if (isRtl) {
                    listOf(curr - 1, curr - 2)
                } else {
                    listOf(curr + 1, curr + 2)
                }

                val validIndices = prefetchIndices.filter { it in images.indices }
                val targetModels = validIndices
                    .filterNot { images[it].isVideo }
                    .mapNotNull { idx ->
                        images[idx].toImageModel(sessionPassword)
                    }

                val zipGroups = targetModels.filterIsInstance<ZipImageSource>()
                    .groupBy { it.zipFile }

                if (coordinator != null) {
                    for ((zipFile, entries) in zipGroups) {
                        coordinator.prefetch(
                            file = zipFile,
                            targetEntryNames = entries.map { it.entryName },
                            password = entries.firstOrNull()?.password
                        )
                    }
                }
            }

            // Horizontal Pager with reverseLayout support for Manga RTL mode
            // beyondViewportPageCount is 0 to ensure 100% CPU is dedicated to the visible page
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 0,
                userScrollEnabled = !isCurrentPageZoomed,
                reverseLayout = (readingMode == ReadingMode.RTL),
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (viewerBackdrop != null) Modifier.layerBackdrop(viewerBackdrop) else Modifier)
            ) { pageIndex ->
                val image = images[pageIndex]
                if (image.isVideo) {
                    // 视频条目无法按图片解码，改为应用内播放入口
                    VideoPage(
                        image = image,
                        onPlay = {
                            onNavigate(
                                AppRoute.VideoPlayer(
                                    packId = image.packId,
                                    entryPath = image.entryPath,
                                    displayName = image.displayName,
                                    source = VideoLauncher.playSourceOf(image)
                                )
                            )
                        }
                    )
                } else {
                    ZoomableImage(
                        image = image,
                        sessionPassword = sessionPassword,
                        onSingleTap = { isImmersive = !isImmersive },
                        onZoomChanged = { isZoomed ->
                            if (pageIndex == pagerState.currentPage) {
                                isCurrentPageZoomed = isZoomed
                            }
                        }
                    )
                }
            }

            val currentDisplayPage = (sliderScrubbingPage ?: pagerState.currentPage) + 1
            val currentImage = images.getOrNull(pagerState.currentPage)

            // 1. Top Floating Frosted Glass Capsule Top Bar (ViewerTopBar)
            AnimatedVisibility(
                visible = !isImmersive,
                enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(220)) + fadeIn(tween(180)),
                exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(200)) + fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                ViewerTopBar(
                    backdrop = viewerBackdrop,
                    fileName = currentImage?.displayName ?: "",
                    currentDisplayPage = currentDisplayPage,
                    totalPages = images.size,
                    readingMode = readingMode,
                    hasSessionPassword = sessionPassword != null,
                    onBack = onBack,
                    onOpenPageSheet = { showPageSheet = true },
                    onOpenImageInfo = { showImageInfoDialog = true },
                    onLockPack = {
                        viewModel.lockPack(packId)
                        onBack()
                    }
                )
            }

            // 2. Bottom Dual-Layer Frosted Glass Control Center (ChapterNavigator + BottomReaderBar)
            AnimatedVisibility(
                visible = !isImmersive,
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(220)) + fadeIn(tween(180)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200)) + fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .widthIn(max = 600.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Upper Layer: RTL Auto-Mirrored Progress Capsule (ChapterNavigator)
                    ChapterNavigator(
                        backdrop = viewerBackdrop,
                        images = images,
                        pagerState = pagerState,
                        readingMode = readingMode,
                        sliderScrubbingPage = sliderScrubbingPage,
                        onScrubPageChange = { targetPage ->
                            if (targetPage != lastHapticPage && targetPage != pagerState.currentPage) {
                                lastHapticPage = targetPage
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            sliderScrubbingPage = targetPage
                            scrubJob?.cancel()
                            scrubJob = coroutineScope.launch {
                                delay(200)
                                if (pagerState.currentPage != targetPage) {
                                    pagerState.scrollToPage(targetPage)
                                }
                                sliderScrubbingPage = null
                            }
                        },
                        onScrubFinished = {
                            scrubJob?.cancel()
                            val targetPage = sliderScrubbingPage
                            if (targetPage != null && pagerState.currentPage != targetPage) {
                                coroutineScope.launch {
                                    pagerState.scrollToPage(targetPage)
                                }
                            }
                            sliderScrubbingPage = null
                            lastHapticPage = -1
                        },
                        onStepPrevious = {
                            val prev = (pagerState.currentPage - 1).coerceAtLeast(0)
                            if (prev != pagerState.currentPage) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                coroutineScope.launch { pagerState.animateScrollToPage(prev) }
                            }
                        },
                        onStepNext = {
                            val next = (pagerState.currentPage + 1).coerceAtMost(images.size - 1)
                            if (next != pagerState.currentPage) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                coroutineScope.launch { pagerState.animateScrollToPage(next) }
                            }
                        }
                    )

                    // Lower Layer: Multi-function Quick Toolbar (BottomReaderBar)
                    BottomReaderBar(
                        backdrop = viewerBackdrop,
                        readingMode = readingMode,
                        orientationMode = orientationMode,
                        isAutoPlay = isAutoPlay,
                        onToggleReadingMode = {
                            viewModel.toggleReadingMode(readingMode)
                        },
                        onCycleOrientation = {
                            val nextMode = orientationMode.next()
                            orientationMode = nextMode
                            (context as? Activity)?.requestedOrientation = nextMode.activityOrientation
                        },
                        onToggleAutoPlay = {
                            isAutoPlay = !isAutoPlay
                        },
                        onOpenPageSheet = {
                            showPageSheet = true
                        }
                    )
                }
            }

            // Page Quick-View Grid Drawer (WindowBottomSheet)
            PageQuickViewBottomSheet(
                show = showPageSheet,
                images = images,
                currentPage = pagerState.currentPage,
                sessionPassword = sessionPassword,
                onSelectPage = { targetIndex ->
                    showPageSheet = false
                    if (targetIndex != pagerState.currentPage) {
                        coroutineScope.launch {
                            pagerState.scrollToPage(targetIndex)
                        }
                    }
                },
                onDismissRequest = { showPageSheet = false }
            )

            // Current Image Details Dialog (WindowDialog)
            if (currentImage != null) {
                ImageDetailsDialog(
                    show = showImageInfoDialog,
                    image = currentImage,
                    pageIndex = pagerState.currentPage,
                    totalPages = images.size,
                    sessionPassword = sessionPassword,
                    onDismissRequest = { showImageInfoDialog = false }
                )
            }
        }
    }
}

/**
 * Floating frosted glass capsule top bar (`ViewerTopBar`).
 */
@Composable
private fun ViewerTopBar(
    backdrop: Backdrop?,
    fileName: String,
    currentDisplayPage: Int,
    totalPages: Int,
    readingMode: ReadingMode,
    hasSessionPassword: Boolean,
    onBack: () -> Unit,
    onOpenPageSheet: () -> Unit,
    onOpenImageInfo: () -> Unit,
    onLockPack: () -> Unit
) {
    val topBarShape = remember { SquircleShape(22.dp) }

    ViewerGlassSurface(
        backdrop = backdrop,
        shape = topBarShape,
        modifier = Modifier
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .widthIn(max = 680.dp)
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = fileName,
                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "第 $currentDisplayPage / $totalPages 页 · ${if (readingMode == ReadingMode.RTL) "日漫右翻" else "标准左翻"}",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(onClick = onOpenPageSheet) {
                Icon(
                    imageVector = Icons.Default.GridView,
                    contentDescription = "页面速览",
                    tint = Color.White
                )
            }

            IconButton(onClick = onOpenImageInfo) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "图片详情",
                    tint = Color.White
                )
            }

            if (hasSessionPassword) {
                IconButton(onClick = onLockPack) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "一键锁定图包",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Upper bottom layer: RTL auto-mirrored progress capsule (`ChapterNavigator`) with floating scrub preview bubble.
 */
@Composable
private fun ChapterNavigator(
    backdrop: Backdrop?,
    images: List<PackImage>,
    pagerState: PagerState,
    readingMode: ReadingMode,
    sliderScrubbingPage: Int?,
    onScrubPageChange: (Int) -> Unit,
    onScrubFinished: () -> Unit,
    onStepPrevious: () -> Unit,
    onStepNext: () -> Unit
) {
    val displayPage = (sliderScrubbingPage ?: pagerState.currentPage) + 1
    val previewBubbleShape = remember { SquircleShape(14.dp) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Floating frosted glass scrub preview bubble
        AnimatedVisibility(
            visible = sliderScrubbingPage != null,
            enter = fadeIn(tween(140)) + slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(160)),
            exit = fadeOut(tween(120)) + slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(140)),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            val scrubIdx = sliderScrubbingPage ?: pagerState.currentPage
            val scrubName = images.getOrNull(scrubIdx)?.displayName ?: ""
            ViewerGlassSurface(
                backdrop = backdrop,
                shape = previewBubbleShape
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .widthIn(min = 120.dp, max = 280.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "第 ${scrubIdx + 1} 页",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    if (scrubName.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = scrubName,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Progress Capsule with RTL auto-mirroring
        ViewerGlassSurface(
            backdrop = backdrop,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (readingMode == ReadingMode.RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onStepPrevious,
                        enabled = pagerState.currentPage > 0
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "上一页",
                            tint = if (pagerState.currentPage > 0) Color.White else Color.White.copy(alpha = 0.35f)
                        )
                    }

                    Slider(
                        value = displayPage.toFloat(),
                        onValueChange = { newVal ->
                            val targetPage = (newVal.roundToInt() - 1).coerceIn(0, images.size - 1)
                            onScrubPageChange(targetPage)
                        },
                        onValueChangeFinished = onScrubFinished,
                        valueRange = 1f..images.size.toFloat().coerceAtLeast(1f),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp)
                    )

                    // Current Page / Total Pages Badge
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$displayPage / ${images.size}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }

                    IconButton(
                        onClick = onStepNext,
                        enabled = pagerState.currentPage < images.size - 1
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "下一页",
                            tint = if (pagerState.currentPage < images.size - 1) Color.White else Color.White.copy(alpha = 0.35f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lower bottom layer: Multi-function quick toolbar (`BottomReaderBar`).
 */
@Composable
private fun BottomReaderBar(
    backdrop: Backdrop?,
    readingMode: ReadingMode,
    orientationMode: ReaderOrientationMode,
    isAutoPlay: Boolean,
    onToggleReadingMode: () -> Unit,
    onCycleOrientation: () -> Unit,
    onToggleAutoPlay: () -> Unit,
    onOpenPageSheet: () -> Unit
) {
    val barShape = remember { SquircleShape(20.dp) }
    val primaryColor = MiuixTheme.colorScheme.primary

    ViewerGlassSurface(
        backdrop = backdrop,
        shape = barShape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReaderBarActionItem(
                icon = Icons.Default.SwapHoriz,
                label = if (readingMode == ReadingMode.RTL) "日漫右翻" else "标准左翻",
                isActive = readingMode == ReadingMode.RTL,
                activeColor = primaryColor,
                onClick = onToggleReadingMode,
                modifier = Modifier.weight(1f)
            )

            ReaderBarActionItem(
                icon = orientationMode.icon,
                label = orientationMode.label,
                isActive = orientationMode != ReaderOrientationMode.SYSTEM,
                activeColor = primaryColor,
                onClick = onCycleOrientation,
                modifier = Modifier.weight(1f)
            )

            ReaderBarActionItem(
                icon = if (isAutoPlay) Icons.Default.PauseCircleOutline else Icons.Default.PlayCircleOutline,
                label = if (isAutoPlay) "自动播放中" else "自动翻页",
                isActive = isAutoPlay,
                activeColor = primaryColor,
                onClick = onToggleAutoPlay,
                modifier = Modifier.weight(1f)
            )

            ReaderBarActionItem(
                icon = Icons.Default.GridView,
                label = "页面速览",
                isActive = false,
                activeColor = primaryColor,
                onClick = onOpenPageSheet,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ReaderBarActionItem(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val itemTint = if (isActive) activeColor else Color.White
    val labelColor = if (isActive) activeColor else Color.White.copy(alpha = 0.85f)
    val itemShape = remember { SquircleShape(14.dp) }

    Column(
        modifier = modifier
            .clip(itemShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = itemTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 3-column thumbnail grid bottom sheet (`WindowBottomSheet`) for instant page preview and jumping.
 */
@Composable
private fun PageQuickViewBottomSheet(
    show: Boolean,
    images: List<PackImage>,
    currentPage: Int,
    sessionPassword: String?,
    onSelectPage: (Int) -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    val primaryColor = MiuixTheme.colorScheme.primary
    val itemShape = remember { SquircleShape(12.dp) }

    LaunchedEffect(show, currentPage) {
        if (show && images.isNotEmpty()) {
            val targetScrollIndex = (currentPage - 3).coerceAtLeast(0)
            gridState.scrollToItem(targetScrollIndex)
        }
    }

    WindowBottomSheet(
        show = show,
        title = "页面速览 (${currentPage + 1} / ${images.size})",
        onDismissRequest = onDismissRequest
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
        ) {
            itemsIndexed(
                items = images,
                key = { index, item -> "${index}_${item.entryPath}" }
            ) { index, item ->
                val isSelected = index == currentPage
                val isVideoItem = item.isVideo
                val thumbModel = remember(item, sessionPassword, isVideoItem) {
                    if (isVideoItem) null else item.toImageModel(sessionPassword, isThumbnail = true, targetSizePx = 300)
                }
                val thumbRequest = remember(thumbModel) {
                    if (thumbModel == null) {
                        null
                    } else {
                        ImageRequest.Builder(context)
                            .data(thumbModel)
                            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                            .precision(Precision.INEXACT)
                            .build()
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f)
                        .clip(itemShape)
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .then(
                            if (isSelected) {
                                Modifier.border(2.5.dp, primaryColor, itemShape)
                            } else {
                                Modifier.border(0.5.dp, Color.White.copy(alpha = 0.12f), itemShape)
                            }
                        )
                        .clickable { onSelectPage(index) }
                ) {
                    if (isVideoItem) {
                        VideoEntryPlaceholder(
                            modifier = Modifier.fillMaxSize(),
                            iconSize = 30.dp
                        )
                    } else if (thumbRequest != null) {
                        AsyncImage(
                            model = thumbRequest,
                            contentDescription = item.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Page Number Badge
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) primaryColor else Color(0xB3000000))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog displaying detailed metadata of the currently viewed image (`WindowDialog`).
 */
@Composable
private fun ImageDetailsDialog(
    show: Boolean,
    image: PackImage,
    pageIndex: Int,
    totalPages: Int,
    sessionPassword: String?,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as? WatchPictureApp
    val diskCache = app?.archiveDiskCache

    val resolvedFile = remember(show, image, sessionPassword) {
        if (!show) return@remember null
        val model = image.toImageModel(sessionPassword)
        when (model) {
            is File -> model.takeIf { it.exists() && it.isFile }
            is ZipImageSource -> diskCache?.get(model.zipFile, model.entryName, model.password)
            else -> null
        }
    }

    val resolutionText = remember(resolvedFile) {
        if (resolvedFile != null && resolvedFile.exists()) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(resolvedFile.absolutePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    "${opts.outWidth} × ${opts.outHeight} px"
                } else {
                    "按需流式解码中"
                }
            }.getOrDefault("按需流式解码中")
        } else {
            "按需流式解码中"
        }
    }

    val fileSizeText = remember(resolvedFile) {
        val bytes = resolvedFile?.length() ?: 0L
        if (bytes > 0L) {
            val kb = bytes / 1024.0
            if (kb >= 1024.0) {
                String.format(Locale.US, "%.2f MB", kb / 1024.0)
            } else {
                String.format(Locale.US, "%.1f KB", kb)
            }
        } else {
            "流式归档条目"
        }
    }

    val formatText = remember(image.displayName) {
        image.displayName.substringAfterLast('.', "IMAGE").uppercase(Locale.US)
    }

    WindowDialog(
        show = show,
        title = "图片详情",
        onDismissRequest = onDismissRequest
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DetailItemRow(label = "文件名称", value = image.displayName)
            DetailItemRow(label = "当前页码", value = "第 ${pageIndex + 1} / $totalPages 页")
            DetailItemRow(label = "图像格式", value = formatText)
            DetailItemRow(label = "图像尺寸", value = resolutionText)
            DetailItemRow(label = "文件大小", value = fileSizeText)
            DetailItemRow(label = "归档路径", value = image.entryPath)
            DetailItemRow(
                label = "加密状态",
                value = if (image.isEncrypted || sessionPassword != null) "已加密保护" else "标准未加密"
            )

            Spacer(modifier = Modifier.height(6.dp))

            Button(
                onClick = onDismissRequest,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "完成",
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun DetailItemRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(76.dp)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

/**
 * 视频页：视频无法按图片解码渲染，这里提供明确的播放入口，
 * 点击后进入应用内的 Media3 播放页（压缩包内条目会按需落盘再播放）。
 */
@Composable
private fun VideoPage(
    image: PackImage,
    onPlay: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { onPlay() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayCircleOutline,
                contentDescription = stringResource(R.string.video_tap_to_play),
                tint = Color.White,
                modifier = Modifier.size(84.dp)
            )
            Text(
                text = image.displayName,
                style = MiuixTheme.textStyles.body1,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

/**
 * Fullscreen high-performance image container powered by Telephoto.
 * Automatically performs sub-sampling (tile-based decoding via BitmapRegionDecoder)
 * for massive 10MB+ images, ensuring laser-sharp clarity without OOM or blurry stretched pixels.
 * Uses cached thumbnail as an instant 0ms placeholder and switches to high-res on load.
 */
@Composable
private fun ZoomableImage(
    image: PackImage,
    sessionPassword: String?,
    onSingleTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as? WatchPictureApp
    val diskCache = app?.archiveDiskCache
    val coordinator = app?.archiveExtractionCoordinator

    val imageModel: Any? = remember(image, sessionPassword) {
        image.toImageModel(sessionPassword)
    }

    if (imageModel != null) {
        // Track whether full-resolution File is available on disk
        var resolvedFile by remember(image, sessionPassword) {
            val initial = when (imageModel) {
                is File -> imageModel
                is ZipImageSource -> {
                    diskCache?.get(imageModel.zipFile, imageModel.entryName, imageModel.password)
                }
                else -> null
            }
            mutableStateOf(initial)
        }

        // On-demand high-priority extraction for currently visible image
        LaunchedEffect(image, sessionPassword) {
            if (resolvedFile == null && imageModel is ZipImageSource && coordinator != null) {
                runCatching {
                    val extracted = coordinator.extractHighPriority(
                        file = imageModel.zipFile,
                        entryName = imageModel.entryName,
                        password = imageModel.password
                    )
                    resolvedFile = extracted
                }
            }
        }

        val zoomableState = rememberZoomableImageState()
        // 用 derivedStateOf 派生布尔量：缩放过程中 zoomFraction 每帧变化，但仅当跨越阈值时才触发重组。
        val isZoomed by remember(zoomableState) {
            derivedStateOf { (zoomableState.zoomableState.zoomFraction ?: 0f) > 0.02f }
        }
        LaunchedEffect(isZoomed) {
            onZoomChanged(isZoomed)
        }

        val activeFile = resolvedFile

        // Hold a lease on the resolved full-resolution cache file while this page is composed, so
        // ArchiveDiskCache's LRU eviction / cleanup cannot delete it mid-decode. The raw File model
        // bypasses ZipImageFetcher, so its lease cannot be attached to Coil's ImageSource here.
        DisposableEffect(activeFile) {
            val lease = activeFile?.let { CacheFileLeases.acquire(it.absolutePath) }
            onDispose { lease?.close() }
        }

        val fullRequest = remember(activeFile) {
            if (activeFile != null) {
                val entryName = activeFile.name.lowercase()
                val hasAlpha = entryName.endsWith(".png") || entryName.endsWith(".webp") || entryName.endsWith(".gif")
                val config = if (Build.VERSION.SDK_INT >= 26) {
                    android.graphics.Bitmap.Config.HARDWARE
                } else if (hasAlpha) {
                    android.graphics.Bitmap.Config.ARGB_8888
                } else {
                    android.graphics.Bitmap.Config.RGB_565
                }
                ImageRequest.Builder(context)
                    .data(activeFile)
                    .bitmapConfig(config)
                    .precision(Precision.EXACT)
                    .build()
            } else {
                null
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onSingleTap() },
            contentAlignment = Alignment.Center
        ) {
            // Immediate preview base layer: prevents black screen while full resolution loads
            val thumbModel = remember(image, sessionPassword) {
                image.toImageModel(sessionPassword, isThumbnail = true, targetSizePx = 360)
            }
            val thumbRequest = remember(thumbModel) {
                val entryName = when (thumbModel) {
                    is ZipImageSource -> thumbModel.entryName
                    is File -> thumbModel.name
                    else -> ""
                }.lowercase()
                val hasAlpha = entryName.endsWith(".png") || entryName.endsWith(".webp") || entryName.endsWith(".gif")
                ImageRequest.Builder(context)
                    .data(thumbModel)
                    .bitmapConfig(if (hasAlpha) android.graphics.Bitmap.Config.ARGB_8888 else android.graphics.Bitmap.Config.RGB_565)
                    .precision(Precision.INEXACT)
                    .build()
            }

            AsyncImage(
                model = thumbRequest,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            // High-resolution interactive ZoomableAsyncImage is only activated when resolvedFile is ready
            if (fullRequest != null) {
                ZoomableAsyncImage(
                    model = fullRequest,
                    contentDescription = image.displayName,
                    state = zoomableState,
                    onClick = { onSingleTap() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
