package com.watchpicture.app.ui.screen

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.watchpicture.app.ui.component.SquircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.crossfade
import coil3.request.bitmapConfig
import kotlinx.coroutines.launch
import com.watchpicture.app.R
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.archive.ZipArchiveManager
import com.watchpicture.app.model.PackImage
import com.watchpicture.app.model.toImageModel
import com.watchpicture.app.navigation.AppRoute
import com.watchpicture.app.ui.component.BlurredBar
import com.watchpicture.app.ui.component.blurBackdropSource
import com.watchpicture.app.ui.component.rememberBlurBackdrop
import com.watchpicture.app.ui.viewmodel.ViewerViewModel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * Thumbnail grid screen displaying all pictures in the selected pack.
 * Optimized for rapid thumbnail preloading and instant jump to full gallery.
 */
@Composable
fun ThumbnailGridScreen(
    packId: String,
    title: String,
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    onNavigate: (AppRoute) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cachedImages = remember(packId) { ViewerViewModel.getCachedImages(packId) }
    val displayImages = if (uiState.images.isNotEmpty()) uiState.images else (cachedImages ?: emptyList())
    val isEffectiveLoading = uiState.isLoading && displayImages.isEmpty()

    val savedBrowseState = remember(packId) { ViewerViewModel.getBrowseState(packId) }
    val browseStates by ViewerViewModel.browseStatesFlow.collectAsStateWithLifecycle()
    val currentBrowseState = browseStates[packId] ?: savedBrowseState

    val scrollBehavior = MiuixScrollBehavior()
    val lazyGridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = savedBrowseState.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = savedBrowseState.firstVisibleItemScrollOffset
    )
    val backdrop = rememberBlurBackdrop()
    val passwordStore = WatchPictureApp.instance.sessionPasswordStore
    val sessionPassword = remember(packId) { passwordStore.get(packId) }

    LaunchedEffect(packId) {
        viewModel.loadImages(packId)
    }

    // Persist grid scroll position continuously so re-entering the pack or returning from viewer
    // preserves the exact scroll offset.
    LaunchedEffect(packId, displayImages.isNotEmpty()) {
        if (displayImages.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            lazyGridState.firstVisibleItemIndex to lazyGridState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collectLatest { (firstIndex, firstOffset) ->
                ViewerViewModel.saveScrollPosition(packId, firstIndex, firstOffset)
            }
    }

    // When returning from fullscreen viewer (or when lastViewedIndex updates), if the user flipped
    // pages outside the currently visible grid viewport, scroll the grid to keep lastViewedIndex in view.
    LaunchedEffect(packId, currentBrowseState.lastViewedIndex, displayImages.size) {
        val lastViewed = currentBrowseState.lastViewedIndex
        if (displayImages.isNotEmpty() && lastViewed in displayImages.indices) {
            // Wait for layout pass if visibleItemsInfo is not yet populated
            androidx.compose.runtime.withFrameNanos { }
            val visibleIndices = lazyGridState.layoutInfo.visibleItemsInfo.map { it.index }
            if (ViewerViewModel.shouldScrollToLastViewed(lastViewed, visibleIndices, displayImages.size)) {
                lazyGridState.scrollToItem(lastViewed)
                ViewerViewModel.saveScrollPosition(
                    packId,
                    lazyGridState.firstVisibleItemIndex,
                    lazyGridState.firstVisibleItemScrollOffset
                )
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(packId) {
        onDispose {
            val app = WatchPictureApp.instance
            app.archiveExtractionCoordinator.cancelBackgroundSweep()
            val file = File(packId)
            if (file.exists() && ZipArchiveManager.isSevenZFile(file)) {
                app.archiveExtractionCoordinator.scheduleCachePurge(file)
            }
        }
    }

    LaunchedEffect(displayImages, packId, sessionPassword) {
        val file = File(packId)
        if (file.exists() && file.isFile && ZipArchiveManager.isSevenZFile(file) && displayImages.isNotEmpty()) {
            val app = WatchPictureApp.instance
            var isScrollPaused = false
            try {
                snapshotFlow {
                    lazyGridState.isScrollInProgress to lazyGridState.firstVisibleItemIndex
                }
                    .distinctUntilChanged()
                    .collectLatest { (isScrolling, firstIndex) ->
                        if (isScrolling) {
                            if (!isScrollPaused) {
                                isScrollPaused = true
                                app.archiveExtractionCoordinator.pauseBackgroundSweep()
                            }
                        } else {
                            if (isScrollPaused) {
                                isScrollPaused = false
                                app.archiveExtractionCoordinator.resumeBackgroundSweep()
                            }
                            // Delay background sweep by 600ms to allow visible viewport thumbnails to load without CPU contention
                            kotlinx.coroutines.delay(600)

                            val totalSize = displayImages.size
                            if (totalSize > 0) {
                                val lastIndex = lazyGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstIndex
                                val start = (firstIndex - 30).coerceAtLeast(0)
                                val end = (lastIndex + 30).coerceAtMost(totalSize - 1)
                                if (start <= end) {
                                    val sublist = displayImages.subList(start, end + 1)
                                    val entryNames = sublist.map { it.entryPath }
                                    app.archiveExtractionCoordinator.startBatchThumbnailSweep(
                                        file = file,
                                        entryNames = entryNames,
                                        targetSizePx = 360,
                                        password = sessionPassword,
                                        thumbnailDiskCache = app.thumbnailDiskCache
                                    )
                                }
                            }
                        }
                    }
            } finally {
                if (isScrollPaused) {
                    isScrollPaused = false
                    app.archiveExtractionCoordinator.resumeBackgroundSweep()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            BlurredBar(
                backdrop = backdrop,
                scrollBehavior = scrollBehavior,
            ) {
                TopAppBar(
                    title = title,
                    scrollBehavior = scrollBehavior,
                    color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    actions = {
                        if (sessionPassword != null) {
                            IconButton(
                                onClick = {
                                    viewModel.lockPack(packId)
                                    onBack()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "锁定图包"
                                )
                            }
                        }
                        if (displayImages.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    val resumeIndex = currentBrowseState.lastViewedIndex
                                        .takeIf { it in displayImages.indices } ?: 0
                                    ViewerViewModel.saveScrollPosition(
                                        packId,
                                        lazyGridState.firstVisibleItemIndex,
                                        lazyGridState.firstVisibleItemScrollOffset
                                    )
                                    ViewerViewModel.saveLastViewedIndex(packId, resumeIndex)
                                    onNavigate(
                                        AppRoute.GalleryViewer(
                                            packId = packId,
                                            initialIndex = resumeIndex
                                        )
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "开始浏览"
                                )
                            }
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blurBackdropSource(backdrop)
        ) {
            when {
                isEffectiveLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            InfiniteProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = MiuixTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.loading),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                        }
                    }
                }

                displayImages.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.5f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "图包内未发现有效图片",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                        }
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        state = lazyGridState,
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        contentPadding = PaddingValues(
                            start = 6.dp,
                            top = innerPadding.calculateTopPadding() + 6.dp,
                            end = 6.dp,
                            bottom = innerPadding.calculateBottomPadding() + 16.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    ) {
                        itemsIndexed(
                            items = displayImages,
                            key = { _, img -> "${img.packId}_${img.entryPath}" }
                        ) { index, item ->
                            ThumbnailItem(
                                image = item,
                                sessionPassword = sessionPassword,
                                index = index + 1,
                                isLastViewed = index == currentBrowseState.lastViewedIndex,
                                onClick = {
                                    ViewerViewModel.saveScrollPosition(
                                        packId,
                                        lazyGridState.firstVisibleItemIndex,
                                        lazyGridState.firstVisibleItemScrollOffset
                                    )
                                    ViewerViewModel.saveLastViewedIndex(packId, index)
                                    onNavigate(
                                        AppRoute.GalleryViewer(
                                            packId = packId,
                                            initialIndex = index
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailItem(
    image: PackImage,
    sessionPassword: String?,
    index: Int,
    isLastViewed: Boolean = false,
    onClick: () -> Unit
) {
    val model: Any? = remember(image, sessionPassword) {
        image.toImageModel(sessionPassword, isThumbnail = true, targetSizePx = 360)
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(model) {
        if (model != null) {
            val entryName = when (model) {
                is com.watchpicture.app.coil.ZipImageSource -> model.entryName
                is java.io.File -> model.name
                else -> ""
            }.lowercase()
            val hasAlpha = entryName.endsWith(".png") || entryName.endsWith(".webp") || entryName.endsWith(".gif")

            coil3.request.ImageRequest.Builder(context)
                .data(model)
                .size(360, 360)
                .precision(coil3.size.Precision.INEXACT)
                .bitmapConfig(if (hasAlpha) android.graphics.Bitmap.Config.ARGB_8888 else android.graphics.Bitmap.Config.RGB_565)
                .build()
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(SquircleShape(8.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = image.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Bottom right index badge (highlighted in primary color for last viewed image)
        Box(
            modifier = Modifier
                .padding(4.dp)
                .align(Alignment.BottomEnd)
                .background(
                    color = if (isLastViewed) MiuixTheme.colorScheme.primary else Color(0xB3000000),
                    shape = SquircleShape(4.dp)
                )
                .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            Text(
                text = "$index",
                fontSize = 10.sp,
                fontWeight = if (isLastViewed) FontWeight.Bold else FontWeight.Medium,
                color = if (isLastViewed) MiuixTheme.colorScheme.onPrimary else Color.White
            )
        }
    }
}
