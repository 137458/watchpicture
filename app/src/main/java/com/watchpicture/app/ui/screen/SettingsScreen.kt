package com.watchpicture.app.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchpicture.app.BuildConfig
import com.watchpicture.app.R
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.model.SortOption
import com.watchpicture.app.storage.ReadingMode
import com.watchpicture.app.ui.component.BlurredBar
import com.watchpicture.app.ui.component.blurBackdropSource
import com.watchpicture.app.ui.component.rememberBlurBackdrop
import com.watchpicture.app.update.UpdateManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.watchpicture.app.ui.component.SquircleShape
import com.watchpicture.app.ui.theme.PaletteStyleOptions
import com.watchpicture.app.ui.theme.PresetColors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField

/**
 * Miuix / HyperOS 规范应用偏好设置页。
 */
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onNavigateToUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val colorScheme = MiuixTheme.colorScheme
    val prefsRepo = WatchPictureApp.instance.preferencesRepository
    val passwordStore = WatchPictureApp.instance.sessionPasswordStore
    val passwordBookRepo = WatchPictureApp.instance.passwordBookRepository
    val updateManager = remember { UpdateManager(context) }

    val darkMode by prefsRepo.darkModeFlow.collectAsStateWithLifecycle(initialValue = 0)
    val colorMode by prefsRepo.colorModeFlow.collectAsStateWithLifecycle(initialValue = 0)
    val seedColor by prefsRepo.seedColorFlow.collectAsStateWithLifecycle(initialValue = 0xFF2196F3L)
    val paletteStyleIndex by prefsRepo.paletteStyleIndexFlow.collectAsStateWithLifecycle(initialValue = 0)
    val useSpec2025 by prefsRepo.useSpec2025Flow.collectAsStateWithLifecycle(initialValue = false)
    val amoledDark by prefsRepo.amoledDarkFlow.collectAsStateWithLifecycle(initialValue = false)
    val wideScreenRail by prefsRepo.wideScreenRailFlow.collectAsStateWithLifecycle(initialValue = true)
    val readingMode by prefsRepo.readingModeFlow.collectAsStateWithLifecycle(initialValue = ReadingMode.LTR)
    val sortOption by prefsRepo.sortOptionFlow.collectAsStateWithLifecycle(initialValue = SortOption.NAME_ASC)
    val savedPasswords by passwordBookRepo.passwordsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val autoSavePassword by passwordBookRepo.autoSavePasswordFlow.collectAsStateWithLifecycle(initialValue = true)

    val isSystemDark = isSystemInDarkTheme()
    val isDarkAppearance = darkMode == 2 || (darkMode == 0 && isSystemDark)

    var showColorPickerDialog by remember { mutableStateOf(false) }
    var showPasswordBookDialog by remember { mutableStateOf(false) }
    var newPasswordInput by remember { mutableStateOf("") }
    var showPlainSavedPasswords by remember { mutableStateOf(false) }

    val paletteStyleLabels = remember { PaletteStyleOptions.map { it.second } }

    val readingModes = remember { listOf(ReadingMode.LTR, ReadingMode.RTL) }
    val readingModeOptions = listOf(
        stringResource(R.string.settings_reading_mode_ltr),
        stringResource(R.string.settings_reading_mode_rtl),
    )
    val currentReadingModeIndex = remember(readingMode, readingModes) {
        readingModes.indexOf(readingMode).coerceAtLeast(0)
    }

    val sortOptions = remember {
        listOf(
            SortOption.NAME_ASC,
            SortOption.NAME_DESC,
            SortOption.TIME_DESC,
            SortOption.TIME_ASC,
            SortOption.SIZE_DESC,
            SortOption.COUNT_DESC,
        )
    }
    val sortOptionLabels = remember {
        listOf(
            "按名称升序 (A → Z)",
            "按名称降序 (Z → A)",
            "按修改时间 (最新优先)",
            "按修改时间 (最早优先)",
            "按体积降序 (大 → 小)",
            "按图片数量 (多 → 少)",
        )
    }
    val currentSortOptionIndex = remember(sortOption, sortOptions) {
        sortOptions.indexOf(sortOption).coerceAtLeast(0)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BlurredBar(
                backdrop = backdrop,
                scrollBehavior = scrollBehavior,
            ) {
                TopAppBar(
                    title = stringResource(R.string.settings_title),
                    scrollBehavior = scrollBehavior,
                    color = if (backdrop != null) Color.Transparent else colorScheme.surface,
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.surface)
                .blurBackdropSource(backdrop),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + contentPadding.calculateBottomPadding() + 24.dp,
                    start = 12.dp,
                    end = 12.dp,
                ),
            ) {
                item {
                    SmallTitle(text = "主题与色彩")
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

                        SwitchPreference(
                            title = "动态色彩 (莫奈取色)",
                            summary = "从系统壁纸自动提取主色调",
                            checked = colorMode == 0,
                            onCheckedChange = { useDynamic ->
                                coroutineScope.launch {
                                    prefsRepo.saveColorMode(if (useDynamic) 0 else 2)
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
                                val isPresetSeed = colorMode == 2 && PresetColors.any { it.second == seedColor }
                                val isCustomSeed = colorMode == 2 && !isPresetSeed

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

                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    SmallTitle(text = stringResource(R.string.settings_section_general))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        WindowDropdownPreference(
                            title = stringResource(R.string.settings_reading_mode_title),
                            items = readingModeOptions,
                            selectedIndex = currentReadingModeIndex,
                            onSelectedIndexChange = { index ->
                                val selected = readingModes.getOrElse(index) { ReadingMode.LTR }
                                coroutineScope.launch {
                                    prefsRepo.saveReadingMode(selected)
                                }
                            },
                        )

                        WindowDropdownPreference(
                            title = stringResource(R.string.settings_default_sort_title),
                            items = sortOptionLabels,
                            selectedIndex = currentSortOptionIndex,
                            onSelectedIndexChange = { index ->
                                val selected = sortOptions.getOrElse(index) { SortOption.NAME_ASC }
                                coroutineScope.launch {
                                    prefsRepo.saveSortOption(selected)
                                }
                            },
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    SmallTitle(text = stringResource(R.string.settings_section_security))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = "密码本管理",
                            summary = if (savedPasswords.isEmpty()) "暂无保存的常用密码" else "已保存 ${savedPasswords.size} 个常用密码",
                            onClick = { showPasswordBookDialog = true },
                        )

                        SwitchPreference(
                            title = "自动记忆密码",
                            summary = "解密成功后自动将密码记入密码本",
                            checked = autoSavePassword,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    passwordBookRepo.setAutoSavePassword(enabled)
                                }
                            }
                        )

                        ArrowPreference(
                            title = stringResource(R.string.settings_clear_passwords_title),
                            summary = stringResource(R.string.settings_clear_passwords_summary),
                            onClick = {
                                passwordStore.clear()
                                Toast.makeText(context, context.getString(R.string.settings_clear_passwords_done), Toast.LENGTH_SHORT).show()
                            },
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    SmallTitle(text = stringResource(R.string.settings_section_about))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = stringResource(R.string.settings_check_update_entry),
                            summary = stringResource(R.string.settings_check_update_entry_summary),
                            onClick = onNavigateToUpdate,
                        )

                        BasicComponent(
                            title = stringResource(R.string.settings_version_title),
                            summary = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
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

    if (showPasswordBookDialog) {
        WindowDialog(
            show = showPasswordBookDialog,
            title = "密码本管理",
            onDismissRequest = { showPasswordBookDialog = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "在此管理已保存的解压密码，打开加密图包时可一键快捷填入：",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (savedPasswords.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无保存的密码",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "点击密码即可显示",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                            IconButton(
                                onClick = { showPlainSavedPasswords = !showPlainSavedPasswords },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (showPlainSavedPasswords) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPlainSavedPasswords) "隐藏所有密码" else "显示所有密码",
                                    tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        savedPasswords.forEach { pwd ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (showPlainSavedPasswords) pwd else "•".repeat(pwd.length),
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            passwordBookRepo.removePassword(pwd)
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "删除此密码",
                                        tint = MiuixTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Input row to add new password
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = newPasswordInput,
                        onValueChange = { newPasswordInput = it },
                        label = "添加新密码…",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val trimmed = newPasswordInput.trim()
                            if (trimmed.isNotEmpty()) {
                                coroutineScope.launch {
                                    passwordBookRepo.addPassword(trimmed)
                                    newPasswordInput = ""
                                }
                            }
                        },
                        enabled = newPasswordInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text("添加")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (savedPasswords.isNotEmpty()) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    passwordBookRepo.clearAll()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("清空全部", color = MiuixTheme.colorScheme.error)
                        }
                    }

                    Button(
                        onClick = { showPasswordBookDialog = false },
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("完成")
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
