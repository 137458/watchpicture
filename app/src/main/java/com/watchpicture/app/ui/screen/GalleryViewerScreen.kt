package com.watchpicture.app.ui.screen

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import coil3.request.crossfade
import coil3.request.bitmapConfig
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.model.PackImage
import com.watchpicture.app.model.toImageModel
import com.watchpicture.app.storage.ReadingMode
import com.watchpicture.app.ui.viewmodel.ViewerViewModel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * Fullscreen picture viewer providing:
 * - HorizontalPager page flipping with dynamic conflict avoidance during zoom
 * - Smooth Animatable double-tap zoom & pinch-to-zoom with strict boundary clamping
 * - Reading direction switching: LTR (standard) and RTL (Japanese Manga)
 * - Single-tap toggle for immersive full-screen mode
 * - Floating bottom controller with MiuixSlider for sub-second rapid jumping
 * - Session lock action to immediately clear memory password
 */
@Composable
fun GalleryViewerScreen(
    packId: String,
    initialIndex: Int,
    viewModel: ViewerViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val readingMode by viewModel.readingMode.collectAsState(initial = ReadingMode.LTR)
    val passwordStore = WatchPictureApp.instance.sessionPasswordStore
    val sessionPassword = remember(packId) { passwordStore.get(packId) }
    val coroutineScope = rememberCoroutineScope()
    var isImmersive by remember { mutableStateOf(false) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }
    var sliderScrubbingPage by remember { mutableStateOf<Int?>(null) }
    var scrubJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Toggle system bars for immersive viewing
    val view = LocalView.current
    val window = (view.context as? Activity)?.window

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

    LaunchedEffect(packId) {
        if (uiState.images.isEmpty()) {
            viewModel.loadImages(packId)
        }
    }

    val images = uiState.images

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (uiState.isLoading || images.isEmpty()) {
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

            // Reset zoom state on page change
            LaunchedEffect(pagerState.currentPage) {
                isCurrentPageZoomed = false
            }

            // Directional background prefetching of adjacent archive entries using coordinator
            val context = androidx.compose.ui.platform.LocalContext.current
            val app = context.applicationContext as? com.watchpicture.app.WatchPictureApp
            val coordinator = app?.archiveExtractionCoordinator

            androidx.compose.runtime.DisposableEffect(packId) {
                onDispose {
                    val file = java.io.File(packId)
                    if (file.exists() && file.isFile) {
                        coordinator?.closeSession(file)
                    }
                }
            }

            LaunchedEffect(pagerState.currentPage, readingMode, images) {
                // Debounce so fast flings skip intermediate pages
                kotlinx.coroutines.delay(100)
                val isRtl = (readingMode == ReadingMode.RTL)
                val curr = pagerState.currentPage
                val prefetchIndices = if (isRtl) {
                    listOf(curr - 1, curr - 2)
                } else {
                    listOf(curr + 1, curr + 2)
                }

                val validIndices = prefetchIndices.filter { it in images.indices }
                val targetModels = validIndices.mapNotNull { idx ->
                    images[idx].toImageModel(sessionPassword)
                }

                val zipGroups = targetModels.filterIsInstance<com.watchpicture.app.coil.ZipImageSource>()
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
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                val image = images[pageIndex]
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

            // Top Floating Controls
            AnimatedVisibility(
                visible = !isImmersive,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x88000000))
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    val currentImage = images.getOrNull(pagerState.currentPage)
                    Text(
                        text = currentImage?.displayName ?: "",
                        style = MiuixTheme.textStyles.title4,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Reading Direction Toggle Button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x55FFFFFF))
                            .clickable { viewModel.toggleReadingMode(readingMode) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "切换阅读方向",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (readingMode == ReadingMode.RTL) "日漫RTL" else "标准LTR",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    // Lock pack button if password authenticated
                    if (sessionPassword != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                viewModel.lockPack(packId)
                                onBack()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "锁定图包",
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    val currentDisplayPage = (sliderScrubbingPage ?: pagerState.currentPage) + 1
                    Text(
                        text = "$currentDisplayPage / ${images.size}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }

            // Bottom Floating Controller Bar with MiuixSlider
            AnimatedVisibility(
                visible = !isImmersive,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val displayPage = (sliderScrubbingPage ?: pagerState.currentPage) + 1

                    // Floating Capsule Control Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xCC1E1E1E))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "$displayPage",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.width(36.dp)
                        )

                        // Miuix Slider for rapid page scrub with 200ms debounce
                        Slider(
                            value = displayPage.toFloat(),
                            onValueChange = { newVal ->
                                val targetPage = (newVal.roundToInt() - 1).coerceIn(0, images.size - 1)
                                sliderScrubbingPage = targetPage
                                scrubJob?.cancel()
                                scrubJob = coroutineScope.launch {
                                    kotlinx.coroutines.delay(200)
                                    if (pagerState.currentPage != targetPage) {
                                        pagerState.scrollToPage(targetPage)
                                    }
                                    sliderScrubbingPage = null
                                }
                            },
                            onValueChangeFinished = {
                                scrubJob?.cancel()
                                val targetPage = sliderScrubbingPage
                                if (targetPage != null && pagerState.currentPage != targetPage) {
                                    coroutineScope.launch {
                                        pagerState.scrollToPage(targetPage)
                                    }
                                }
                                sliderScrubbingPage = null
                            },
                            valueRange = 1f..images.size.toFloat().coerceAtLeast(1f),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )

                        Text(
                            text = "${images.size}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.width(36.dp)
                        )
                    }
                }
            }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as? com.watchpicture.app.WatchPictureApp
    val diskCache = app?.archiveDiskCache
    val coordinator = app?.archiveExtractionCoordinator

    val imageModel: Any? = remember(image, sessionPassword) {
        image.toImageModel(sessionPassword)
    }

    if (imageModel != null) {
        // Track whether full-resolution File is available on disk
        var resolvedFile by remember(image, sessionPassword) {
            val initial = when (imageModel) {
                is java.io.File -> imageModel
                is com.watchpicture.app.coil.ZipImageSource -> {
                    diskCache?.get(imageModel.zipFile, imageModel.entryName, imageModel.password)
                }
                else -> null
            }
            androidx.compose.runtime.mutableStateOf(initial)
        }

        // On-demand high-priority extraction for currently visible image
        LaunchedEffect(image, sessionPassword) {
            if (resolvedFile == null && imageModel is com.watchpicture.app.coil.ZipImageSource && coordinator != null) {
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

        val zoomableState = me.saket.telephoto.zoomable.rememberZoomableImageState()
        val zoomFraction: Float? = zoomableState.zoomableState.zoomFraction
        val isZoomed = (zoomFraction ?: 0f) > 0.02f
        LaunchedEffect(isZoomed) {
            onZoomChanged(isZoomed)
        }

        val activeFile = resolvedFile

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onSingleTap() },
            contentAlignment = Alignment.Center
        ) {
            // Immediate 0ms thumbnail preview base layer: guarantees zero black screen regardless of cache state
            val thumbModel = remember(image, sessionPassword) {
                image.toImageModel(sessionPassword, isThumbnail = true, targetSizePx = 360)
            }
            val thumbRequest = remember(thumbModel) {
                coil3.request.ImageRequest.Builder(context)
                    .data(thumbModel)
                    .precision(coil3.size.Precision.INEXACT)
                    .build()
            }

            coil3.compose.AsyncImage(
                model = thumbRequest,
                contentDescription = image.displayName,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            // High-resolution seekable tile layer seamlessly overlays on top once ready
            if (activeFile != null) {
                val fullRequest = remember(activeFile) {
                    coil3.request.ImageRequest.Builder(context)
                        .data(activeFile)
                        .crossfade(150)
                        .precision(coil3.size.Precision.EXACT)
                        .build()
                }

                me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage(
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
