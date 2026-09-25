package com.watchpicture.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
