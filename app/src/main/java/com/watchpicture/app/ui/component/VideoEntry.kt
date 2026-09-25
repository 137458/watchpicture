package com.watchpicture.app.ui.component

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.watchpicture.app.R
import com.watchpicture.app.archive.VideoLauncher
import com.watchpicture.app.model.PackImage
import top.yukonga.miuix.kmp.basic.Icon

/**
 * 视频条目的统一占位：深色底 + 播放图标。缩略图网格、图包封面与画廊速览共用。
 */
@Composable
fun VideoEntryPlaceholder(
    modifier: Modifier = Modifier,
    iconSize: Dp = 34.dp
) {
    Box(
        modifier = modifier.background(Color(0xFF1B1B1F)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 把视频条目交给系统播放器播放，失败时给出统一提示。
 */
suspend fun playVideoEntry(context: Context, item: PackImage) {
    if (!VideoLauncher.play(context, item)) {
        Toast.makeText(
            context,
            context.getString(R.string.video_no_player),
            Toast.LENGTH_SHORT
        ).show()
    }
}
