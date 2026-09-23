package com.watchpicture.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchpicture.app.R
import com.watchpicture.app.update.UpdateCheckResult
import com.watchpicture.app.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024.0 && unitIndex < units.size - 1) {
        size /= 1024.0
        unitIndex++
    }
    return "%.1f %s".format(size, units[unitIndex])
}

fun formatSpeed(bytesPerSec: Long): String = "${formatFileSize(bytesPerSec)}/s"

/**
 * 官方 Miuix / HyperOS 规范版本更新弹窗。
 */
@Composable
fun UpdateDialog(
    show: Boolean,
    releaseInfo: UpdateCheckResult,
    onDismiss: () -> Unit,
    onUpdate: (url: String) -> Unit = {},
    onIgnore: ((version: String) -> Unit)? = null,
    externalScope: CoroutineScope? = null,
) {
    val context = LocalContext.current
    // “后台下载”需要比弹窗组合树更长的生命周期：优先使用调用方传入的 scope，
    // 未传入时退回组件内部 scope（此时弹窗移除会取消下载）。
    val internalScope = rememberCoroutineScope()
    val coroutineScope = externalScope ?: internalScope
    val updateManager = remember { UpdateManager(context) }

    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(0L) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    var downloadSpeed by remember { mutableLongStateOf(0L) }
    var lastSpeedUpdateTime by remember { mutableLongStateOf(0L) }
    var lastSpeedBytes by remember { mutableLongStateOf(0L) }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        isDownloading = false
        downloadProgress = 0f
        downloadedBytes = 0L
        downloadSpeed = 0L
    }

    fun startDownload() {
        val downloadUrl = releaseInfo.downloadUrl ?: releaseInfo.releaseUrl

        isDownloading = true
        downloadError = null
        downloadProgress = 0f
        downloadedBytes = 0L
        totalBytes = 0L
        downloadSpeed = 0L
        lastSpeedUpdateTime = System.currentTimeMillis()
        lastSpeedBytes = 0L

        downloadJob = coroutineScope.launch {
            val result = updateManager.downloadApk(
                downloadUrl = downloadUrl,
                onProgress = { progress, downloaded, total ->
                    downloadProgress = progress
                    downloadedBytes = downloaded
                    totalBytes = total

                    val now = System.currentTimeMillis()
                    val dt = now - lastSpeedUpdateTime
                    if (dt >= 400L) {
                        val dBytes = downloaded - lastSpeedBytes
                        if (dBytes > 0L) {
                            val instantSpeed = (dBytes * 1000L) / dt
                            downloadSpeed = if (downloadSpeed == 0L) {
                                instantSpeed
                            } else {
                                (downloadSpeed * 7 + instantSpeed * 3) / 10
                            }
                        }
                        lastSpeedUpdateTime = now
                        lastSpeedBytes = downloaded
                    }
                },
            )
            isDownloading = false
            downloadJob = null
            result
                .onSuccess { file ->
                    downloadedFile = file
                    updateManager.installApk(context, file)
                }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        downloadError = error.localizedMessage ?: "下载失败"
                    }
                }
        }
    }

    WindowDialog(
        show = show,
        title = stringResource(R.string.update_dialog_title),
        summary = "${releaseInfo.currentVersion} ➔ ${releaseInfo.latestVersion}",
        onDismissRequest = {
            if (!isDownloading) {
                onDismiss()
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 版本与发布元信息横栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = releaseInfo.latestVersion,
                            style = MiuixTheme.textStyles.body2.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            ),
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                    if (releaseInfo.apkSize > 0L) {
                        Text(
                            text = formatFileSize(releaseInfo.apkSize),
                            style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
                if (releaseInfo.publishedAt.isNotBlank()) {
                    Text(
                        text = releaseInfo.publishedAt,
                        style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            // 更新日志容器卡片
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp, max = 220.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (releaseInfo.changelog.isNotBlank()) {
                    MarkdownText(
                        markdown = releaseInfo.changelog,
                        modifier = Modifier.fillMaxWidth(),
                        baseFontSize = 13,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.update_dialog_no_changelog),
                        style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            // 动态状态提示区
            if (isDownloading) {
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val speedText = if (downloadSpeed > 0L) " · ${formatSpeed(downloadSpeed)}" else ""
                        Text(
                            text = stringResource(R.string.update_dialog_downloading, speedText),
                            style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            color = MiuixTheme.colorScheme.primary,
                        )
                        val percent = if (downloadProgress >= 0f) "${(downloadProgress * 100).toInt()}%" else ""
                        val sizeText = if (totalBytes > 0L) {
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
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = downloadProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (downloadedFile != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.update_dialog_ready_install),
                        style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp),
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            } else if (downloadError != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.error.copy(alpha = 0.08f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.update_dialog_download_failed, downloadError ?: ""),
                        style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        color = MiuixTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.update_dialog_network_hint),
                        style = MiuixTheme.textStyles.body2.copy(fontSize = 11.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 底部操作按钮栏
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                when {
                    isDownloading -> {
                        Button(
                            onClick = { cancelDownload() },
                            colors = ButtonDefaults.buttonColors(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.update_dialog_btn_cancel_download))
                        }
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.update_dialog_btn_background_download))
                        }
                    }
                    downloadedFile != null -> {
                        Button(
                            onClick = { startDownload() },
                            colors = ButtonDefaults.buttonColors(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.update_dialog_btn_redownload))
                        }
                        Button(
                            onClick = {
                                updateManager.installApk(context, downloadedFile!!)
                            },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.weight(1.3f),
                        ) {
                            Text(stringResource(R.string.update_dialog_btn_install))
                        }
                    }
                    downloadError != null -> {
                        Button(
                            onClick = {
                                val url = releaseInfo.downloadUrl ?: releaseInfo.releaseUrl
                                updateManager.openInBrowser(context, url)
                            },
                            colors = ButtonDefaults.buttonColors(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.update_dialog_btn_browser))
                        }
                        Button(
                            onClick = { startDownload() },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.update_dialog_btn_retry))
                        }
                    }
                    else -> {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.update_dialog_btn_later))
                        }
                        Button(
                            onClick = {
                                if (releaseInfo.downloadUrl != null) {
                                    startDownload()
                                } else {
                                    onUpdate(releaseInfo.releaseUrl)
                                    updateManager.openInBrowser(context, releaseInfo.releaseUrl)
                                }
                            },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.weight(1.2f),
                        ) {
                            Text(stringResource(R.string.update_dialog_btn_update))
                        }
                    }
                }
            }

            // 忽略此版本选项
            if (onIgnore != null && !isDownloading && downloadedFile == null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.update_dialog_btn_ignore),
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            onIgnore(releaseInfo.latestVersion)
                            onDismiss()
                        }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}
