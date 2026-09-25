package com.watchpicture.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 预置主题种子色列表，对齐参考应用 (YunX / PixEz-Miuix)。
 */
val PresetColors: List<Pair<String, Long>> = listOf(
    "蓝色" to 0xFF2196F3L,
    "靛蓝" to 0xFF3F51B5L,
    "紫色" to 0xFF7B1FA2L,
    "玫红" to 0xFFC2185BL,
    "红色" to 0xFFD32F2FL,
    "橙色" to 0xFFE65100L,
    "金黄" to 0xFFF9A825L,
    "绿色" to 0xFF2E7D32L,
    "青色" to 0xFF00838FL,
    "天蓝" to 0xFF0277BDL,
)

/**
 * 调色板生成风格名称映射。
 */
val PaletteStyleOptions: List<Pair<ThemePaletteStyle, String>> = listOf(
    ThemePaletteStyle.TonalSpot to "色调斑点 (TonalSpot - 默认)",
    ThemePaletteStyle.Neutral to "中性柔和 (Neutral)",
    ThemePaletteStyle.Vibrant to "鲜艳明快 (Vibrant)",
    ThemePaletteStyle.Expressive to "表现力 (Expressive)",
    ThemePaletteStyle.Rainbow to "彩虹色板 (Rainbow)",
    ThemePaletteStyle.FruitSalad to "缤纷果盘 (FruitSalad)",
    ThemePaletteStyle.Monochrome to "纯粹黑白 (Monochrome)",
    ThemePaletteStyle.Fidelity to "高保真还原 (Fidelity)",
    ThemePaletteStyle.Content to "内容匹配 (Content)",
)

/**
 * 深浅外观模式名称，用于设置页入口的当前状态摘要。
 */
fun themeAppearanceLabel(darkMode: Int): String = when (darkMode) {
    1 -> "浅色"
    2 -> "深色"
    else -> "跟随系统"
}

/**
 * 色彩来源名称: 0=动态色彩, 1=默认色板, 2=预置种子色(回显色名)或自定义种子色。
 */
fun themeColorSourceLabel(colorMode: Int, seedColor: Long): String = when (colorMode) {
    0 -> "动态色彩"
    1 -> "默认色板"
    else -> PresetColors.firstOrNull { it.second == seedColor }?.first ?: "自定义"
}

/**
 * 兼容旧版单一索引的主题调色板模式解析。
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
 * 解耦深浅外观模式与色彩来源模式的 Miuix ColorSchemeMode 映射：
 * - darkMode: 0=跟随系统, 1=浅色, 2=深色
 * - colorMode: 0=动态色彩(壁纸莫奈), 1=默认固定调色板, 2=自定义/预置种子色(keyColor)
 *
 * 注意：Miuix ThemeController 仅在 Monet* 模式下消费 keyColor 生成算法色板，
 * 因此 colorMode == 2 必须映射至 MonetSystem / MonetLight / MonetDark。
 */
fun resolveColorSchemeMode(darkMode: Int, colorMode: Int): ColorSchemeMode = when (colorMode) {
    0, 2 -> when (darkMode) {
        1 -> ColorSchemeMode.MonetLight
        2 -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.MonetSystem
    }
    1 -> when (darkMode) {
        1 -> ColorSchemeMode.Light
        2 -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
    else -> ColorSchemeMode.MonetSystem
}

/**
 * 浅色模式分层背景方案：页面画布采用柔和冷灰 (#F6F7F9)，卡片容器采用纯白 (#FFFFFF)，
 * 确保 Miuix Card 与底栏在浅色外观下具备清晰层次对比。
 */
fun defaultLightColors(): Colors = lightColorScheme().copy(
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFF6F7F9),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0F1F4),
    surfaceContainerHighest = Color(0xFFE5E7EB),
)

/**
 * AMOLED 纯黑覆盖：在深色基准方案上替换背景与容器灰阶层次，保留主色与文字色。
 */
fun applyAmoledColors(base: Colors): Colors = base.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF121212),
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color(0xFF1E1E1E),
    surfaceContainerHighest = Color(0xFF2C2C2C),
)

/**
 * 构建 Miuix ThemeController：完整支持动态色彩、预置/自定义种子色、调色板风格、Spec2025 与 AMOLED 纯黑。
 */
fun buildMiuixThemeController(
    darkMode: Int,
    colorMode: Int,
    seedColor: Long,
    amoledDark: Boolean,
    paletteStyleIndex: Int,
    useSpec2025: Boolean,
): ThemeController {
    val schemeMode = resolveColorSchemeMode(darkMode, colorMode)
    val keyColor = if (colorMode == 2) Color(seedColor) else null
    val paletteStyle = ThemePaletteStyle.entries.getOrElse(paletteStyleIndex) {
        ThemePaletteStyle.TonalSpot
    }
    val colorSpec = if (useSpec2025) ThemeColorSpec.Spec2025 else ThemeColorSpec.Spec2021
    val lightColors = defaultLightColors()
    val baseDarkColors = darkColorScheme()
    val darkColors = if (amoledDark) applyAmoledColors(baseDarkColors) else baseDarkColors

    return ThemeController(
        colorSchemeMode = schemeMode,
        lightColors = lightColors,
        darkColors = darkColors,
        keyColor = keyColor,
        paletteStyle = paletteStyle,
        colorSpec = colorSpec,
    )
}

/**
 * 全局应用主题包装器：驱动 Miuix 多维色彩系统与 AMOLED 纯黑叠加。
 */
@Composable
fun WatchPictureTheme(
    darkMode: Int = 0,
    colorMode: Int = 1,
    seedColor: Long = 0xFF2196F3L,
    amoledDark: Boolean = false,
    paletteStyleIndex: Int = 0,
    useSpec2025: Boolean = false,
    content: @Composable () -> Unit,
) {
    val controller = remember(darkMode, colorMode, seedColor, amoledDark, paletteStyleIndex, useSpec2025) {
        buildMiuixThemeController(
            darkMode = darkMode,
            colorMode = colorMode,
            seedColor = seedColor,
            amoledDark = amoledDark,
            paletteStyleIndex = paletteStyleIndex,
            useSpec2025 = useSpec2025,
        )
    }

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
