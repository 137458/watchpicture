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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.watchpicture.app.BuildConfig
import com.watchpicture.app.R
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.model.SortOption
import com.watchpicture.app.storage.ReadingMode
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
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
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
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val prefsRepo = WatchPictureApp.instance.preferencesRepository
    val passwordStore = WatchPictureApp.instance.sessionPasswordStore
    val passwordBookRepo = WatchPictureApp.instance.passwordBookRepository
    val updateManager = remember { UpdateManager(context) }

    val readingMode by prefsRepo.readingModeFlow.collectAsState(initial = ReadingMode.LTR)
    val sortOption by prefsRepo.sortOptionFlow.collectAsState(initial = SortOption.NAME_ASC)
    val savedPasswords by passwordBookRepo.passwordsFlow.collectAsState(initial = emptyList())
    val autoSavePassword by passwordBookRepo.autoSavePasswordFlow.collectAsState(initial = true)

    var showReadingModeDialog by remember { mutableStateOf(false) }
    var showPasswordBookDialog by remember { mutableStateOf(false) }
    var newPasswordInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + contentPadding.calculateBottomPadding() + 24.dp,
                start = 12.dp,
                end = 12.dp,
            ),
        ) {
            item {
                SmallTitle(text = stringResource(R.string.settings_section_general))
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_reading_mode_title),
                        summary = if (readingMode == ReadingMode.LTR) {
                            stringResource(R.string.settings_reading_mode_ltr)
                        } else {
                            stringResource(R.string.settings_reading_mode_rtl)
                        },
                        onClick = { showReadingModeDialog = true },
                    )

                    ArrowPreference(
                        title = stringResource(R.string.settings_default_sort_title),
                        summary = when (sortOption) {
                            SortOption.NAME_ASC -> "按名称升序 (A → Z)"
                            SortOption.NAME_DESC -> "按名称降序 (Z → A)"
                            SortOption.TIME_DESC -> "按修改时间 (最新优先)"
                            SortOption.TIME_ASC -> "按修改时间 (最早优先)"
                            SortOption.SIZE_DESC -> "按体积降序 (大 → 小)"
                            SortOption.COUNT_DESC -> "按图片数量 (多 → 少)"
                        },
                        onClick = {
                            val next = when (sortOption) {
                                SortOption.NAME_ASC -> SortOption.NAME_DESC
                                SortOption.NAME_DESC -> SortOption.TIME_DESC
                                SortOption.TIME_DESC -> SortOption.TIME_ASC
                                SortOption.TIME_ASC -> SortOption.SIZE_DESC
                                SortOption.SIZE_DESC -> SortOption.COUNT_DESC
                                SortOption.COUNT_DESC -> SortOption.NAME_ASC
                            }
                            coroutineScope.launch {
                                prefsRepo.saveSortOption(next)
                            }
                        }
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

    if (showReadingModeDialog) {
        WindowDialog(
            show = showReadingModeDialog,
            title = stringResource(R.string.settings_reading_mode_title),
            onDismissRequest = { showReadingModeDialog = false },
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = stringResource(R.string.settings_reading_mode_ltr),
                    summary = "默认从左向右翻页",
                    onClick = {
                        coroutineScope.launch {
                            prefsRepo.saveReadingMode(ReadingMode.LTR)
                            showReadingModeDialog = false
                        }
                    }
                )
                BasicComponent(
                    title = stringResource(R.string.settings_reading_mode_rtl),
                    summary = "日本漫画右起左翻翻页",
                    onClick = {
                        coroutineScope.launch {
                            prefsRepo.saveReadingMode(ReadingMode.RTL)
                            showReadingModeDialog = false
                        }
                    }
                )
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
                                    text = pwd,
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
                                        tint = Color(0xFFE53935),
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
                            Text("清空全部", color = Color(0xFFE53935))
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
