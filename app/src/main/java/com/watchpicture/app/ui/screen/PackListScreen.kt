package com.watchpicture.app.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.watchpicture.app.R
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.model.SortOption
import com.watchpicture.app.navigation.AppRoute
import com.watchpicture.app.ui.component.PackCard
import com.watchpicture.app.ui.component.PasswordDialog
import com.watchpicture.app.ui.viewmodel.PackViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Main screen displaying the list/grid of picture packs.
 * Features:
 * - SAF root directory selection with DataStore persistence
 * - Real-time pack search & fuzzy filtering
 * - Multi-dimensional sorting (Name, Date, Size, Count)
 * - Miuix password authentication with auto-unlock fallback
 */
@Composable
fun PackListScreen(
    viewModel: PackViewModel,
    onNavigate: (AppRoute) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()
    val haptic = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val passwordStore = WatchPictureApp.instance.sessionPasswordStore
    var showSortDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onRootFolderSelected(uri)
        }
    }

    val archiveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.openSingleArchive(uri, onNavigate)
        }
    }

    val openArchiveSelector = {
        archiveLauncher.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/x-cbz",
                "*/*"
            )
        )
    }

    Scaffold(
        topBar = {
            if (uiState.isSearchActive) {
                TopAppBar(
                    title = "",
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = { viewModel.setSearchActive(false) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "退出搜索"
                            )
                        }
                    },
                    actions = {
                        TextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.onSearchQueryChanged(it) },
                            label = "搜索图包名称…",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            trailingIcon = {
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { viewModel.onSearchQueryChanged("") },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "清空"
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 12.dp)
                        )
                    }
                )
            } else {
                TopAppBar(
                    title = stringResource(R.string.app_name),
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(
                            onClick = { viewModel.setSearchActive(true) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索"
                            )
                        }
                        IconButton(
                            onClick = { showSortDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "排序"
                            )
                        }
                        IconButton(
                            onClick = { viewModel.refresh() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh)
                            )
                        }
                        IconButton(
                            onClick = openArchiveSelector
                        ) {
                            Icon(
                                imageVector = Icons.Default.Archive,
                                contentDescription = stringResource(R.string.open_archive)
                            )
                        }
                        IconButton(
                            onClick = { folderLauncher.launch(null) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = stringResource(R.string.select_folder)
                            )
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(contentPadding)
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            InfiniteProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = MiuixTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.loading),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                        }
                    }
                }

                uiState.packs.isEmpty() && uiState.rootUri == null -> {
                    // Empty state: No root folder selected yet and no standalone archive opened
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "欢迎使用图包快捷查看器",
                                style = MiuixTheme.textStyles.title2,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.pick_folder_prompt),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { folderLauncher.launch(null) },
                                    colors = ButtonDefaults.buttonColorsPrimary()
                                ) {
                                    Text(stringResource(R.string.select_folder))
                                }
                                Button(
                                    onClick = openArchiveSelector,
                                    colors = ButtonDefaults.buttonColors()
                                ) {
                                    Text(stringResource(R.string.open_archive))
                                }
                            }
                        }
                    }
                }

                uiState.packs.isEmpty() -> {
                    // Empty directory
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.5f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = stringResource(R.string.empty_pack_hint),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { folderLauncher.launch(null) },
                                    colors = ButtonDefaults.buttonColors()
                                ) {
                                    Text(stringResource(R.string.change_folder))
                                }
                                Button(
                                    onClick = openArchiveSelector,
                                    colors = ButtonDefaults.buttonColorsPrimary()
                                ) {
                                    Text(stringResource(R.string.open_archive))
                                }
                            }
                        }
                    }
                }

                uiState.displayedPacks.isEmpty() && uiState.searchQuery.isNotEmpty() -> {
                    // No search results
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.5f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "未找到与「${uiState.searchQuery}」匹配的图包",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.onSearchQueryChanged("") },
                                colors = ButtonDefaults.buttonColors()
                            ) {
                                Text("清空搜索")
                            }
                        }
                    }
                }

                else -> {
                    // Grid of picture packs
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = uiState.displayedPacks,
                            key = { it.id }
                        ) { pack ->
                            PackCard(
                                pack = pack,
                                sessionPassword = passwordStore.get(pack.id),
                                onClick = {
                                    viewModel.onPackClicked(pack, onNavigate)
                                }
                            )
                        }
                    }
                }
            }
        }

        // Sorting Selection Dialog
        if (showSortDialog) {
            SortOptionDialog(
                currentOption = uiState.sortOption,
                onDismiss = { showSortDialog = false },
                onOptionSelected = { option ->
                    viewModel.onSortOptionSelected(option)
                    showSortDialog = false
                }
            )
        }

        // Miuix Password Dialog for Encrypted Packs
        if (uiState.showPasswordDialog && uiState.targetZipPack != null) {
            val target = uiState.targetZipPack!!
            PasswordDialog(
                show = uiState.showPasswordDialog,
                packName = target.name,
                errorMessage = uiState.passwordError,
                isVerifying = uiState.isVerifyingPassword,
                onDismissRequest = { viewModel.dismissPasswordDialog() },
                onConfirm = { password ->
                    viewModel.verifyAndUnlockPassword(
                        password = password,
                        onSuccess = {
                            onNavigate(
                                AppRoute.ThumbnailGrid(
                                    packId = target.id,
                                    title = target.name
                                )
                            )
                        },
                        onFailureHaptic = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun SortOptionDialog(
    currentOption: SortOption,
    onDismiss: () -> Unit,
    onOptionSelected: (SortOption) -> Unit
) {
    val options = listOf(
        SortOption.NAME_ASC to "名称升序 (A-Z 自然排序)",
        SortOption.NAME_DESC to "名称降序 (Z-A 自然排序)",
        SortOption.TIME_DESC to "修改时间 (最新优先)",
        SortOption.TIME_ASC to "修改时间 (最早优先)",
        SortOption.SIZE_DESC to "文件体积 (从大到小)",
        SortOption.COUNT_DESC to "图片总数 (从多到少)"
    )

    WindowDialog(
        show = true,
        title = "排序方式",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            options.forEach { (option, label) ->
                val isSelected = option == currentOption
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOptionSelected(option) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.body1,
                        color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Text(
                            text = "✓",
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
