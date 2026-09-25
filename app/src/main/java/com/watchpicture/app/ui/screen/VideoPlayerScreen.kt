package com.watchpicture.app.ui.screen

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.watchpicture.app.R
import com.watchpicture.app.archive.VideoLauncher
import com.watchpicture.app.model.PackImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text

/**
 * Application-internal video player screen backed by Media3/ExoPlayer.
 *
 * 图包内的视频条目（含压缩包内条目）先经 [VideoLauncher] 解析为可播放来源，
 * 再由 ExoPlayer 在本页内直接播放，不再跳出到第三方播放器；
 * 仅当本机解码器无法播放（例如不支持的编码）时才提供外部播放器兜底入口。
 */
@Composable
fun VideoPlayerScreen(
    packId: String,
    entryPath: String,
    displayName: String,
    source: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    val item = remember(packId, entryPath, displayName, source) {
        PackImage(
            packId = packId,
            entryPath = entryPath,
            displayName = displayName,
            directFilePath = source.takeIf { !it.startsWith("content://") },
            fileUri = source.takeIf { it.startsWith("content://") }
        )
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
        }
    }

    DisposableEffect(player, item) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        isLoading = false
                        durationMs = player.duration.coerceAtLeast(0L)
                    }
                    Player.STATE_ENDED -> isPlaying = false
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                isLoading = false
                isPlaying = false
                com.watchpicture.app.util.AppLog.e("VideoPlay", "ExoPlayer 播放失败: ${item.displayName}", error)
                errorMessage = error.localizedMessage ?: context.getString(R.string.video_play_failed)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.stop()
            player.clearMediaItems()
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    LaunchedEffect(item) {
        val playable = VideoLauncher.resolvePlayable(context, item)
        if (playable == null) {
            isLoading = false
            errorMessage = context.getString(R.string.video_play_failed)
            return@LaunchedEffect
        }
        player.setMediaItem(MediaItem.fromUri(playable.uri))
        player.prepare()
        player.playWhenReady = true
    }

    // 进度与总时长轮询：ExoPlayer 不提供位置回调，这里按 500ms 采样即可满足进度条需求
    LaunchedEffect(player) {
        while (true) {
            if (!isScrubbing) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
            }
            if (durationMs <= 0L) {
                durationMs = player.duration.coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    BackHandler(onBack = onBack)

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val activity = view.context as? Activity
        val insetsController = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            view.keepScreenOn = false
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { controlsVisible = !controlsVisible }
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { surface -> player.setVideoSurfaceView(surface) }
            },
            modifier = Modifier.fillMaxSize()
        )

        val message = errorMessage
        if (message != null) {
            PlaybackFailurePanel(
                message = message,
                onBack = onBack,
                onOpenExternally = {
                    coroutineScope.launch { VideoLauncher.play(context, item) }
                }
            )
        } else if (isLoading) {
            InfiniteProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp),
                color = Color.White
            )
        }

        if (controlsVisible && message == null) {
            VideoPlayerControls(
                title = item.displayName,
                isPlaying = isPlaying,
                positionMs = if (isScrubbing) scrubPositionMs else positionMs,
                durationMs = durationMs,
                onBack = onBack,
                onTogglePlay = {
                    if (player.isPlaying) player.pause() else player.play()
                },
                onScrub = { value ->
                    isScrubbing = true
                    scrubPositionMs = value.toLong()
                },
                onScrubFinished = {
                    player.seekTo(scrubPositionMs.coerceAtLeast(0L))
                    positionMs = scrubPositionMs
                    isScrubbing = false
                }
            )
        }
    }
}

/**
 * 播放控制层：顶部返回与标题、中部播放/暂停、底部进度与时间。
 */
@Composable
private fun VideoPlayerControls(
    title: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .systemBarsPadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onTogglePlay() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = onTogglePlay,
            modifier = Modifier
                .align(Alignment.Center)
                .size(72.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(56.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = formatPlaybackTime(positionMs),
                fontSize = 12.sp,
                color = Color.White
            )
            Slider(
                value = positionMs.toFloat(),
                onValueChange = onScrub,
                onValueChangeFinished = onScrubFinished,
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatPlaybackTime(durationMs),
                fontSize = 12.sp,
                color = Color.White
            )
        }
    }
}

@Composable
private fun PlaybackFailurePanel(
    message: String,
    onBack: () -> Unit,
    onOpenExternally: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                text = message,
                fontSize = 14.sp,
                color = Color.White,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors()
                ) {
                    Text("返回")
                }
                Button(
                    onClick = onOpenExternally,
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text("用其它播放器打开")
                }
            }
        }
    }
}

/**
 * 把毫秒格式化为 `mm:ss`，超过一小时时格式化为 `h:mm:ss`。
 */
internal fun formatPlaybackTime(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L)) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }
}
