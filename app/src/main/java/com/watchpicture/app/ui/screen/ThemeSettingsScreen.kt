package com.watchpicture.app.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchpicture.app.R
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.ui.component.BlurredBar
import com.watchpicture.app.ui.component.SquircleShape
import com.watchpicture.app.ui.component.blurBackdropSource
import com.watchpicture.app.ui.component.rememberBlurBackdrop
import com.watchpicture.app.ui.theme.PaletteStyleOptions
import com.watchpicture.app.ui.theme.PresetColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 独立主题与色彩设置页：承载深浅外观、色彩来源、种子色、调色板风格与界面布局偏好，
 * 从主设置页拆出以降低信息密度。
 */
@Composable
fun ThemeSettingsScreen(
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val colorScheme = MiuixTheme.colorScheme
    val prefsRepo = WatchPictureApp.instance.preferencesRepository

    val darkMode by prefsRepo.darkModeFlow.collectAsStateWithLifecycle(initialValue = 0)
    val colorMode by prefsRepo.colorModeFlow.collectAsStateWithLifecycle(initialValue = 1)
    val seedColor by prefsRepo.seedColorFlow.collectAsStateWithLifecycle(initialValue = 0xFF2196F3L)
    val paletteStyleIndex by prefsRepo.paletteStyleIndexFlow.collectAsStateWithLifecycle(initialValue = 0)
    val useSpec2025 by prefsRepo.useSpec2025Flow.collectAsStateWithLifecycle(initialValue = false)
    val amoledDark by prefsRepo.amoledDarkFlow.collectAsStateWithLifecycle(initialValue = false)
    val wideScreenRail by prefsRepo.wideScreenRailFlow.collectAsStateWithLifecycle(initialValue = true)

    val isSystemDark = isSystemInDarkTheme()
    val isDarkAppearance = darkMode == 2 || (darkMode == 0 && isSystemDark)

    var showColorPickerDialog by remember { mutableStateOf(false) }

    val paletteStyleLabels = remember { PaletteStyleOptions.map { it.second } }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BlurredBar(
                backdrop = backdrop,
                scrollBehavior = scrollBehavior,
            ) {
                SmallTopAppBar(
                    title = stringResource(R.string.theme_settings_title),
                    scrollBehavior = scrollBehavior,
                    color = if (backdrop != null) Color.Transparent else colorScheme.surface,
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
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blurBackdropSource(backdrop),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                    start = 12.dp,
                    end = 12.dp,
                ),
            ) {
                item {
                    SmallTitle(text = stringResource(R.string.theme_settings_title))
                    ThemePreviewCard()
                    Spacer(modifier = Modifier.height(10.dp))

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Text(
                                text = "深浅外观",
                                style = MiuixTheme.textStyles.headline2,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    0 to "跟随系统",
                                    1 to "浅色",
                                    2 to "深色"
                                ).forEach { (modeVal, label) ->
                                    AppearanceModeChip(
                                        selected = darkMode == modeVal,
                                        label = label,
                                        onClick = {
                                            coroutineScope.launch {
                                                prefsRepo.saveDarkMode(modeVal)
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        SwitchPreference(
                            title = "AMOLED 纯黑",
                            summary = if (isDarkAppearance) {
                                "将背景替换为纯黑以节省 OLED 电量"
                            } else {
                                "仅在深色外观模式下生效"
                            },
                            checked = amoledDark,
                            enabled = isDarkAppearance,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    prefsRepo.saveAmoledDark(enabled)
                                }
                            },
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    SmallTitle(text = "色彩来源")
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            title = "动态色彩 (莫奈取色)",
                            summary = "从系统壁纸自动提取主色调，关闭时使用固定色板",
                            checked = colorMode == 0,
                            onCheckedChange = { useDynamic ->
                                coroutineScope.launch {
                                    prefsRepo.saveColorMode(if (useDynamic) 0 else 1)
                                }
                            },
                        )

                        if (colorMode != 0) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, bottom = 12.dp)
                            ) {
                                Text(
                                    text = "主题种子色",
                                    style = MiuixTheme.textStyles.body2,
                                    fontWeight = FontWeight.Medium,
                                    color = colorScheme.onSurfaceSecondary,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                val isCustomSeed = colorMode == 2 && PresetColors.none { it.second == seedColor }

                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Miuix 经典固定配色方案 (colorMode == 1)
                                    item {
                                        SeedColorOptionItem(
                                            name = "默认色板",
                                            color = Color(0xFF3482FF),
                                            isSelected = colorMode == 1,
                                            onClick = {
                                                coroutineScope.launch {
                                                    prefsRepo.saveColorMode(1)
                                                }
                                            }
                                        )
                                    }

                                    // 10 种精选种子色 (colorMode == 2)
                                    items(PresetColors) { (name, colorLong) ->
                                        SeedColorOptionItem(
                                            name = name,
                                            color = Color(colorLong),
                                            isSelected = colorMode == 2 && seedColor == colorLong,
                                            onClick = {
                                                coroutineScope.launch {
                                                    prefsRepo.saveSeedColor(colorLong)
                                                    prefsRepo.saveColorMode(2)
                                                }
                                            }
                                        )
                                    }

                                    // 自定义取色器 (colorMode == 2)
                                    item {
                                        CustomSeedColorItem(
                                            color = Color(seedColor),
                                            isSelected = isCustomSeed,
                                            onClick = { showColorPickerDialog = true }
                                        )
                                    }
                                }
                            }
                        }

                        WindowDropdownPreference(
                            title = "调色板风格",
                            summary = "控制莫奈与种子色衍生调色板的饱和度与色相策略",
                            items = paletteStyleLabels,
                            selectedIndex = paletteStyleIndex.coerceIn(0, paletteStyleLabels.lastIndex),
                            onSelectedIndexChange = { index ->
                                coroutineScope.launch {
                                    prefsRepo.savePaletteStyleIndex(index)
                                }
                            },
                        )

                        SwitchPreference(
                            title = "2025 色彩规范 (Spec2025)",
                            summary = "启用 Material 3 2025 扩展高对比调色板算法",
                            checked = useSpec2025,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    prefsRepo.saveUseSpec2025(enabled)
                                }
                            },
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    SmallTitle(text = "界面布局")
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            title = "宽屏侧边导航栏",
                            summary = "宽屏或平板设备上使用左侧导航栏替代悬浮底栏",
                            checked = wideScreenRail,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    prefsRepo.saveWideScreenRail(enabled)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showColorPickerDialog) {
        ColorPickerDialog(
            show = showColorPickerDialog,
            initialColor = Color(seedColor),
            onDismiss = { showColorPickerDialog = false },
            onConfirm = { pickedColor ->
                val argbLong = (pickedColor.toArgb().toLong() and 0xFFFFFFFFL) or 0xFF000000L
                coroutineScope.launch {
                    prefsRepo.saveSeedColor(argbLong)
                    prefsRepo.saveColorMode(2)
                }
                showColorPickerDialog = false
            }
        )
    }
}

@Composable
private fun ThemePreviewCard() {
    val colorScheme = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "当前主题预览",
                style = MiuixTheme.textStyles.body2,
                color = colorScheme.onSurfaceSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(SquircleShape(12.dp))
                        .background(colorScheme.primary)
                        .padding(12.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Text(
                        text = "主色调",
                        color = colorScheme.onPrimary,
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(2f)
                        .height(56.dp)
                        .clip(SquircleShape(12.dp))
                        .background(colorScheme.surface)
                        .border(1.dp, colorScheme.dividerLine, SquircleShape(12.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column {
                        Text(
                            text = "表面与正文对比",
                            color = colorScheme.onSurface,
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "次级说明与容器灰阶层次",
                            color = colorScheme.onSurfaceSecondary,
                            style = MiuixTheme.textStyles.footnote1
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    colorScheme.surfaceContainer,
                    colorScheme.surfaceContainerHigh,
                    colorScheme.surfaceContainerHighest
                ).forEach { containerColor ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(20.dp)
                            .clip(SquircleShape(6.dp))
                            .background(containerColor)
                            .border(0.5.dp, colorScheme.dividerLine, SquircleShape(6.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceModeChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.surfaceContainerHigh
        },
        label = "chip_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MiuixTheme.colorScheme.onPrimary
        } else {
            MiuixTheme.colorScheme.onSurface
        },
        label = "chip_fg"
    )

    Box(
        modifier = modifier
            .height(38.dp)
            .clip(SquircleShape(10.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = contentColor,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SeedColorOptionItem(
    name: String,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(SquircleShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color)
                .then(
                    if (isSelected) {
                        Modifier.border(3.dp, MiuixTheme.colorScheme.onSurface, CircleShape)
                    } else {
                        Modifier.border(1.dp, MiuixTheme.colorScheme.dividerLine, CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = name,
            style = MiuixTheme.textStyles.footnote1,
            color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun CustomSeedColorItem(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(SquircleShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (isSelected) color else MiuixTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (isSelected) {
                        Modifier.border(3.dp, MiuixTheme.colorScheme.onSurface, CircleShape)
                    } else {
                        Modifier.border(1.dp, MiuixTheme.colorScheme.dividerLine, CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.Check else Icons.Default.Palette,
                contentDescription = "自定义颜色",
                tint = if (isSelected) Color.White else MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "自定义",
            style = MiuixTheme.textStyles.footnote1,
            color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun ColorPickerDialog(
    show: Boolean,
    initialColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit
) {
    var selectedColor by remember(initialColor) { mutableStateOf(initialColor) }
    var hexText by remember(initialColor) {
        val hex = String.format("%06X", 0xFFFFFF and initialColor.toArgb())
        mutableStateOf("#$hex")
    }

    WindowDialog(
        title = "自定义种子色",
        summary = "拖动取色器或输入十六进制色值，自动衍生全套 Miuix 色板",
        show = show,
        onDismissRequest = onDismiss,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ColorPicker(
                color = selectedColor,
                onColorChanged = { newColor ->
                    selectedColor = newColor
                    val hex = String.format("%06X", 0xFFFFFF and newColor.toArgb())
                    hexText = "#$hex"
                },
                showPreview = true,
            )

            TextField(
                value = hexText,
                onValueChange = { input ->
                    val cleaned = input.uppercase().take(7)
                    hexText = cleaned
                    val rawHex = cleaned.removePrefix("#")
                    if (rawHex.length == 6) {
                        rawHex.toLongOrNull(16)?.let { rgb ->
                            selectedColor = Color(rgb or 0xFF000000L)
                        }
                    }
                },
                label = "HEX (#RRGGBB)",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消")
                }
                Button(
                    onClick = { onConfirm(selectedColor) },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("确定")
                }
            }
        }
    }
}
