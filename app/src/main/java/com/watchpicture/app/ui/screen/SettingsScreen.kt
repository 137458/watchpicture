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
import androidx.compose.runtime.LaunchedEffect
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
import com.watchpicture.app.ui.component.formatFileSize
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
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.watchpicture.app.ui.theme.themeAppearanceLabel
import com.watchpicture.app.ui.theme.themeColorSourceLabel
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
    onNavigateToThemeSettings: () -> Unit,
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
    val colorMode by prefsRepo.colorModeFlow.collectAsStateWithLifecycle(initialValue = 1)
    val seedColor by prefsRepo.seedColorFlow.collectAsStateWithLifecycle(initialValue = 0xFF2196F3L)
    val readingMode by prefsRepo.readingModeFlow.collectAsStateWithLifecycle(initialValue = ReadingMode.LTR)
    val sortOption by prefsRepo.sortOptionFlow.collectAsStateWithLifecycle(initialValue = SortOption.NAME_ASC)
    val savedPasswords by passwordBookRepo.passwordsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val autoSavePassword by passwordBookRepo.autoSavePasswordFlow.collectAsStateWithLifecycle(initialValue = true)
    val autoCleanCache by prefsRepo.autoCleanCacheFlow.collectAsStateWithLifecycle(initialValue = true)

    var showPasswordBookDialog by remember { mutableStateOf(false) }
    var newPasswordInput by remember { mutableStateOf("") }
    var showPlainSavedPasswords by remember { mutableStateOf(false) }
    var cacheSizeBytes by remember { mutableStateOf(0L) }
    var isClearingCache by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        cacheSizeBytes = WatchPictureApp.instance.totalCacheSizeBytes()
    }

    val themeSummary = "${themeAppearanceLabel(darkMode)} · ${themeColorSourceLabel(colorMode, seedColor)}"

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
                    SmallTitle(text = stringResource(R.string.theme_settings_title))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = stringResource(R.string.theme_settings_title),
                            summary = themeSummary,
                            onClick = onNavigateToThemeSettings,
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
                    SmallTitle(text = stringResource(R.string.settings_section_storage))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        BasicComponent(
                            title = stringResource(R.string.settings_cache_usage_title),
                            summary = formatFileSize(cacheSizeBytes),
                        )

                        ArrowPreference(
                            title = stringResource(R.string.settings_cache_clear_title),
                            summary = stringResource(R.string.settings_cache_clear_summary),
                            onClick = {
                                if (!isClearingCache) {
                                    isClearingCache = true
                                    coroutineScope.launch {
                                        val freed = WatchPictureApp.instance.clearAllCaches()
                                        cacheSizeBytes = WatchPictureApp.instance.totalCacheSizeBytes()
                                        isClearingCache = false
                                        val message = if (freed > 0L) {
                                            context.getString(R.string.settings_cache_cleared, formatFileSize(freed))
                                        } else {
                                            context.getString(R.string.settings_cache_empty)
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        )

                        SwitchPreference(
                            title = stringResource(R.string.settings_cache_auto_title),
                            summary = stringResource(R.string.settings_cache_auto_summary),
                            checked = autoCleanCache,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    prefsRepo.saveAutoCleanCache(enabled)
                                }
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
    }
}

