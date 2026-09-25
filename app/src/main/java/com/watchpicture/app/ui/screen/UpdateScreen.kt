package com.watchpicture.app.ui.screen

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchpicture.app.BuildConfig
import com.watchpicture.app.R
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.ui.component.BlurredBar
import com.watchpicture.app.ui.component.MarkdownText
import com.watchpicture.app.ui.component.UpdateDialog
import com.watchpicture.app.ui.component.blurBackdropSource
import com.watchpicture.app.ui.component.formatFileSize
import com.watchpicture.app.ui.component.rememberBlurBackdrop
import com.watchpicture.app.ui.effect.BgEffectBackground
import com.watchpicture.app.ui.effect.isRuntimeShaderSupported
import com.watchpicture.app.update.UpdateCheckResult
import com.watchpicture.app.update.UpdateManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import com.watchpicture.app.ui.component.SquircleShape
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 官方 Miuix / HyperOS 3.0 规范系统与应用更新页。
 * 集成 HyperOS 动态流光背景、横竖屏自适应 Hero 视差层级、超椭圆图标容器与卡片偏好系统。
 */
@Composable
fun UpdateScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val updateManager = remember { UpdateManager(context) }
    val prefsRepo = WatchPictureApp.instance.preferencesRepository
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()

    val autoCheckUpdate by prefsRepo.autoCheckUpdateFlow.collectAsStateWithLifecycle(initialValue = true)
    val ignoredVersion by prefsRepo.ignoredVersionFlow.collectAsStateWithLifecycle(initialValue = null)

    var releaseInfo by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(0L) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }

    val hasNew = releaseInfo?.hasUpdate == true

    fun doCheck(userInitiated: Boolean = false) {
        if (isChecking) return
        isChecking = true
        coroutineScope.launch {
            val result = updateManager.checkForUpdate()
            isChecking = false
            result.onSuccess { info ->
                releaseInfo = info
                if (info.hasUpdate) {
                    if (userInitiated || info.latestVersion != ignoredVersion) {
                        showDialog = true
                    }
                } else if (userInitiated) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.update_latest_header, BuildConfig.VERSION_NAME),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { error ->
                val message = error.localizedMessage ?: ""
                if (userInitiated) {
                    Toast.makeText(context, "检查更新失败: $message", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (autoCheckUpdate) {
            doCheck(userInitiated = false)
        }
    }

    val scrollProgress by remember {
        derivedStateOf {
            when {
                lazyListState.firstVisibleItemIndex > 0 -> 1f
                else -> {
                    val spacer = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "logoSpacer" }
                    if (spacer != null && spacer.size > 0) {
                        (lazyListState.firstVisibleItemScrollOffset.toFloat() / spacer.size).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
        }
    }

    val density = LocalDensity.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var logoHeightDp by remember { mutableStateOf(if (isLandscape) 120.dp else 240.dp) }
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        topBar = {
            val barColor = if (backdrop != null) {
                Color.Transparent
            } else if (scrollProgress == 1f) {
                MiuixTheme.colorScheme.surface
            } else {
                Color.Transparent
            }
            val titleColor = MiuixTheme.colorScheme.onSurface.copy(
                alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
            )
            BlurredBar(
                backdrop = backdrop,
                scrollBehavior = topAppBarScrollBehavior,
            ) {
                SmallTopAppBar(
                    title = stringResource(R.string.update_screen_title),
                    scrollBehavior = topAppBarScrollBehavior,
                    color = barColor,
                    titleColor = titleColor,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.nav_back),
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blurBackdropSource(backdrop),
            contentAlignment = Alignment.TopCenter,
        ) {
            BgEffectBackground(
                dynamicBackground = isRuntimeShaderSupported(),
                isOs3Effect = true,
                isFullSize = true,
                modifier = Modifier.fillMaxSize(),
                alpha = { 1f - scrollProgress },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // ── 顶部官方规范横竖屏自适应 Hero 视觉 ──
                    if (isLandscape) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    top = innerPadding.calculateTopPadding() + 8.dp,
                                    start = 24.dp,
                                    end = 24.dp,
                                )
                                .onSizeChanged { size ->
                                    with(density) { logoHeightDp = size.height.toDp() }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            UpdateHeroIcon(compact = true, scrollProgress = scrollProgress)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(
                                modifier = Modifier.graphicsLayer {
                                    val nameProgress = ((scrollProgress - 0.20f) / 0.15f).coerceIn(0f, 1f)
                                    alpha = 1 - nameProgress
                                    scaleX = 1 - (nameProgress * 0.05f)
                                    scaleY = 1 - (nameProgress * 0.05f)
                                },
                            ) {
                                UpdateHeroTitle(compact = true, scrollProgress = scrollProgress)
                                Spacer(modifier = Modifier.height(2.dp))
                                UpdateHeroStatus(
                                    compact = true,
                                    isChecking = isChecking,
                                    hasNew = hasNew,
                                    releaseInfo = releaseInfo,
                                    scrollProgress = scrollProgress,
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = innerPadding.calculateTopPadding() + 24.dp)
                                .onSizeChanged { size ->
                                    with(density) { logoHeightDp = size.height.toDp() }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            UpdateHeroIcon(compact = false, scrollProgress = scrollProgress)
                            Spacer(modifier = Modifier.height(16.dp))
                            UpdateHeroTitle(compact = false, scrollProgress = scrollProgress)
                            Spacer(modifier = Modifier.height(6.dp))
                            UpdateHeroStatus(
                                compact = false,
                                isChecking = isChecking,
                                hasNew = hasNew,
                                releaseInfo = releaseInfo,
                                scrollProgress = scrollProgress,
                            )
                        }
                    }

                    // ── 滚动内容列表 ──
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 760.dp)
                            .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding() + 24.dp,
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        item(key = "logoSpacer") {
                            val spacerExtra = if (isLandscape) 12.dp else 48.dp
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(logoHeightDp + spacerExtra),
                            )
                        }

                        // ── 更新日志卡片（带发布日期、版本胶囊徽章与下载进度条） ──
                        if (releaseInfo != null) {
                            item(key = "changelog") {
                                val changelogTitle = if (hasNew) {
                                    stringResource(R.string.update_changelog_title_new, releaseInfo?.latestVersion ?: "")
                                } else {
                                    stringResource(R.string.update_changelog_title_current, BuildConfig.VERSION_NAME)
                                }
                                val defaultReleaseTitle = stringResource(R.string.update_release_default_title)
                                SmallTitle(text = changelogTitle)
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp),
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        // 版本胶囊徽章与发布日期元信息栏
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(SquircleShape(6.dp))
                                                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                                ) {
                                                    Text(
                                                        text = releaseInfo?.latestVersion ?: "v${BuildConfig.VERSION_NAME}",
                                                        style = MiuixTheme.textStyles.body2.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 12.sp,
                                                        ),
                                                        color = MiuixTheme.colorScheme.primary,
                                                    )
                                                }
                                                val apkSize = releaseInfo?.apkSize ?: 0L
                                                if (apkSize > 0L) {
                                                    Text(
                                                        text = formatFileSize(apkSize),
                                                        style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp),
                                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                    )
                                                }
                                            }
                                            val publishedAt = releaseInfo?.publishedAt.orEmpty()
                                            if (publishedAt.isNotBlank()) {
                                                Text(
                                                    text = publishedAt,
                                                    style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp),
                                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                )
                                            }
                                        }

                                        Text(
                                            text = releaseInfo?.releaseTitle?.ifBlank { defaultReleaseTitle } ?: defaultReleaseTitle,
                                            style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Bold),
                                            color = MiuixTheme.colorScheme.onSurface,
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        MarkdownText(
                                            markdown = releaseInfo?.changelog ?: "",
                                            modifier = Modifier.fillMaxWidth(),
                                            baseFontSize = 14,
                                        )

                                        if (isDownloading) {
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.update_downloading_hint),
                                                    style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp),
                                                    color = MiuixTheme.colorScheme.primary,
                                                )
                                                val percent = if (downloadProgress >= 0f) "${(downloadProgress * 100).toInt()}%" else ""
                                                val sizeText = if (totalBytes > 0) {
                                                    "${formatFileSize(downloadedBytes)} / ${formatFileSize(totalBytes)}"
                                                } else {
                                                    formatFileSize(downloadedBytes)
                                                }
                                                Text(
                                                    text = if (percent.isNotEmpty()) "$sizeText ($percent)" else sizeText,
                                                    style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp),
                                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            LinearProgressIndicator(
                                                progress = downloadProgress,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }

                                        if (downloadedFile != null || (hasNew && !isDownloading)) {
                                            Spacer(modifier = Modifier.height(16.dp))
                                        }

                                        if (downloadedFile != null) {
                                            TextButton(
                                                text = stringResource(R.string.update_btn_install_now),
                                                onClick = {
                                                    updateManager.installApk(context, downloadedFile!!)
                                                },
                                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        } else if (hasNew && !isDownloading) {
                                            TextButton(
                                                text = stringResource(R.string.update_btn_download_now),
                                                onClick = {
                                                    val url = releaseInfo?.downloadUrl ?: releaseInfo?.releaseUrl
                                                    if (url != null) {
                                                        isDownloading = true
                                                        downloadProgress = 0f
                                                        coroutineScope.launch {
                                                            val result = updateManager.downloadApk(
                                                                downloadUrl = url,
                                                                onProgress = { progress, downloaded, total ->
                                                                    downloadProgress = progress
                                                                    downloadedBytes = downloaded
                                                                    totalBytes = total
                                                                },
                                                            )
                                                            isDownloading = false
                                                            result.onSuccess { file ->
                                                                downloadedFile = file
                                                                updateManager.installApk(context, file)
                                                            }.onFailure { error ->
                                                                Toast.makeText(
                                                                    context,
                                                                    "下载 APK 失败: ${error.localizedMessage ?: ""}",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        }
                                                    } else {
                                                        releaseInfo?.releaseUrl?.let { updateManager.openInBrowser(context, it) }
                                                    }
                                                },
                                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }

                        // ── 当前版本状态与更新设置 ──
                        item(key = "settings") {
                            SmallTitle(text = stringResource(R.string.update_section_settings))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                            ) {
                                BasicComponent(
                                    title = stringResource(R.string.settings_version_title),
                                    summary = if (hasNew) {
                                        "当前 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · 最新 ${releaseInfo?.latestVersion}"
                                    } else {
                                        "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                                    },
                                )

                                SwitchPreference(
                                    title = stringResource(R.string.update_pref_auto_check_title),
                                    summary = stringResource(R.string.update_pref_auto_check_summary),
                                    checked = autoCheckUpdate,
                                    onCheckedChange = { checked ->
                                        coroutineScope.launch {
                                            prefsRepo.saveAutoCheckUpdate(checked)
                                        }
                                    },
                                )

                                BasicComponent(
                                    title = stringResource(R.string.update_pref_manual_check_title),
                                    summary = if (isChecking) {
                                        stringResource(R.string.update_checking_hint)
                                    } else {
                                        stringResource(R.string.update_pref_manual_check_summary)
                                    },
                                    onClick = {
                                        if (!isChecking) {
                                            doCheck(userInitiated = true)
                                        }
                                    },
                                    endActions = {
                                        if (isChecking) {
                                            InfiniteProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                color = MiuixTheme.colorScheme.primary,
                                            )
                                        }
                                    },
                                )

                                ArrowPreference(
                                    title = "前往 GitHub Releases 查看全部版本",
                                    summary = UpdateManager.RELEASES_URL,
                                    onClick = {
                                        updateManager.openInBrowser(context, UpdateManager.RELEASES_URL)
                                    },
                                )

                                ArrowPreference(
                                    title = stringResource(R.string.settings_github_repo),
                                    summary = UpdateManager.REPO_URL,
                                    onClick = {
                                        updateManager.openInBrowser(context, UpdateManager.REPO_URL)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog && releaseInfo != null) {
        UpdateDialog(
            show = showDialog,
            releaseInfo = releaseInfo!!,
            onDismiss = { showDialog = false },
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

@Composable
private fun UpdateHeroIcon(
    compact: Boolean,
    scrollProgress: Float,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(if (compact) 56.dp else 88.dp)
            .graphicsLayer {
                val iconProgress = ((scrollProgress - 0.35f) / 0.15f).coerceIn(0f, 1f)
                clip = true
                shape = SquircleShape(if (compact) 16.dp else 24.dp)
                alpha = 1 - iconProgress
                scaleX = 1 - (iconProgress * 0.05f)
                scaleY = 1 - (iconProgress * 0.05f)
            }
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        SafeAppIcon(modifier = Modifier.size(if (compact) 36.dp else 56.dp))
    }
}

@Composable
private fun UpdateHeroTitle(
    compact: Boolean,
    scrollProgress: Float,
) {
    Text(
        text = stringResource(R.string.app_name),
        style = if (compact) {
            MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.Bold)
        } else {
            MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.Bold)
        },
        color = MiuixTheme.colorScheme.onSurface,
        modifier = Modifier.graphicsLayer {
            val nameProgress = ((scrollProgress - 0.20f) / 0.15f).coerceIn(0f, 1f)
            alpha = 1 - nameProgress
            scaleX = 1 - (nameProgress * 0.05f)
            scaleY = 1 - (nameProgress * 0.05f)
        },
    )
}

@Composable
private fun UpdateHeroStatus(
    compact: Boolean,
    isChecking: Boolean,
    hasNew: Boolean,
    releaseInfo: UpdateCheckResult?,
    scrollProgress: Float,
) {
    val colorScheme = MiuixTheme.colorScheme
    val fontSize = if (compact) 12.sp else 14.sp
    val textAlign = if (compact) null else TextAlign.Center
    val modifier = if (compact) {
        Modifier
    } else {
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val verProgress = ((scrollProgress - 0.05f) / 0.15f).coerceIn(0f, 1f)
                alpha = 1 - verProgress
            }
    }
    when {
        isChecking -> Text(
            text = stringResource(R.string.update_checking_hint),
            color = colorScheme.onSurfaceVariantSummary,
            fontSize = fontSize,
            textAlign = textAlign,
            modifier = modifier,
        )
        hasNew -> Text(
            text = stringResource(
                R.string.update_found_header,
                releaseInfo?.latestVersion ?: "",
                BuildConfig.VERSION_NAME,
            ),
            color = colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize,
            textAlign = textAlign,
            modifier = modifier,
        )
        else -> Text(
            text = stringResource(R.string.update_latest_header, BuildConfig.VERSION_NAME),
            color = colorScheme.onSurfaceVariantSummary,
            fontSize = fontSize,
            textAlign = textAlign,
            modifier = modifier,
        )
    }
}

@Composable
private fun SafeAppIcon(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appIconBitmap = remember(context) {
        try {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }

    if (appIconBitmap != null) {
        Image(
            bitmap = appIconBitmap,
            contentDescription = stringResource(R.string.app_name),
            modifier = modifier,
        )
    } else {
        // 前景层是透明底的 108dp 矢量，单独使用会缺自适应图标的渐变底色，这里补一层底色与超椭圆裁切
        Box(
            modifier = modifier
                .clip(SquircleShape(12.dp))
                .background(Color(0xFF0E2F73)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
