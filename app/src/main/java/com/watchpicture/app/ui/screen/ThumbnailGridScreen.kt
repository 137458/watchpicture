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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.crossfade
import coil3.request.bitmapConfig
import kotlinx.coroutines.launch
import com.watchpicture.app.R
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.model.PackImage
import com.watchpicture.app.model.toImageModel
import com.watchpicture.app.navigation.AppRoute
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
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()
    val passwordStore = WatchPictureApp.instance.sessionPasswordStore
    val sessionPassword = remember(packId) { passwordStore.get(packId) }

    LaunchedEffect(packId) {
        viewModel.loadImages(packId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                scrollBehavior = scrollBehavior,
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
                    if (uiState.images.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                onNavigate(
                                    AppRoute.GalleryViewer(
                                        packId = packId,
                                        initialIndex = 0
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
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
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

                uiState.images.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
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
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        contentPadding = PaddingValues(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = uiState.images,
                            key = { index, img -> "${img.packId}_${img.entryPath}_$index" }
                        ) { index, item ->
                            ThumbnailItem(
                                image = item,
                                sessionPassword = sessionPassword,
                                index = index + 1,
                                onClick = {
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

            val builder = coil3.request.ImageRequest.Builder(context)
                .data(model)
                .size(360, 360)
                .precision(coil3.size.Precision.INEXACT)
                .crossfade(100)
                .bitmapConfig(if (hasAlpha) android.graphics.Bitmap.Config.ARGB_8888 else android.graphics.Bitmap.Config.RGB_565)

            val thumbKey = when (model) {
                is com.watchpicture.app.coil.ZipImageSource -> {
                    val pwdHash = model.password?.hashCode()?.toString(16) ?: "none"
                    "thumb:zip://${model.zipFile.absolutePath}#${model.entryName}#pwd=$pwdHash#sz=360"
                }
                is java.io.File -> "thumb:file://${model.absolutePath}#sz=360"
                else -> null
            }
            if (thumbKey != null) {
                builder.memoryCacheKey(thumbKey)
            }
            builder.build()
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
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

        // Bottom right index badge
        Box(
            modifier = Modifier
                .padding(4.dp)
                .align(Alignment.BottomEnd)
                .background(Color(0xB3000000), shape = RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            Text(
                text = "$index",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}
