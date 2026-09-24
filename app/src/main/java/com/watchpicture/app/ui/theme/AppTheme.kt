package com.watchpicture.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 解析主题索引对应的 Miuix 调色板模式。
 * 0=跟随系统, 1=浅色, 2=深色, 3=莫奈跟随系统, 4=莫奈浅色, 5=莫奈深色，其余默认 MonetSystem。
 */
fun resolveColorSchemeMode(themeModeIndex: Int): ColorSchemeMode = when (themeModeIndex) {
    0 -> ColorSchemeMode.System
    1 -> ColorSchemeMode.Light
    2 -> ColorSchemeMode.Dark
    3 -> ColorSchemeMode.MonetSystem
    4 -> ColorSchemeMode.MonetLight
    5 -> ColorSchemeMode.MonetDark
    else -> ColorSchemeMode.MonetSystem
}

/**
 * AMOLED 纯黑覆盖：在深色基准方案上替换背景与容器灰阶，保留主色与文字色。
 */
fun applyAmoledColors(base: Colors): Colors = base.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainer = Color(0xFF0D0D0D),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF262626),
)

/**
 * 全局应用主题包装器：驱动 Miuix 色彩模式与 AMOLED 纯黑叠加。
 */
@Composable
fun WatchPictureTheme(
    themeModeIndex: Int,
    amoledDark: Boolean,
    content: @Composable () -> Unit,
) {
    val colorSchemeMode = remember(themeModeIndex) { resolveColorSchemeMode(themeModeIndex) }
    val controller = remember(colorSchemeMode) { ThemeController(colorSchemeMode) }

    MiuixTheme(controller = controller) {
        val base = MiuixTheme.colorScheme
        if (base.surface.luminance() < 0.5f && amoledDark) {
            val amoledColors = remember(base.background, base.surface, base.primary) {
                applyAmoledColors(base)
            }
            MiuixTheme(colors = amoledColors) {
                content()
            }
        } else {
            content()
        }
    }
}
