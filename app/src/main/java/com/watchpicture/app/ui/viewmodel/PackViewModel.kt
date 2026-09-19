package com.watchpicture.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.model.DirectoryPack
import com.watchpicture.app.model.PackItem
import com.watchpicture.app.model.ZipPack
import com.watchpicture.app.navigation.AppRoute
import com.watchpicture.app.storage.SafManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PackListUiState(
    val isLoading: Boolean = false,
    val rootUri: Uri? = null,
    val packs: List<PackItem> = emptyList(),
    val errorMessage: String? = null,
    // Password dialog state
    val showPasswordDialog: Boolean = false,
    val targetZipPack: ZipPack? = null,
    val passwordError: String? = null,
    val isVerifyingPassword: Boolean = false
)

class PackViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WatchPictureApp
    private val zipArchiveManager = app.zipArchiveManager
    private val passwordStore = app.sessionPasswordStore
    private val safManager = SafManager(zipArchiveManager, passwordStore)

    private val _uiState = MutableStateFlow(PackListUiState())
    val uiState: StateFlow<PackListUiState> = _uiState.asStateFlow()

    fun onRootFolderSelected(uri: Uri) {
        safManager.takePersistablePermission(app, uri)
        _uiState.update { it.copy(rootUri = uri) }
        loadPacks(uri)
    }

    fun refresh() {
        val root = _uiState.value.rootUri ?: return
        loadPacks(root)
    }

    private fun loadPacks(treeUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val packs = safManager.scanPacks(app, treeUri)
                _uiState.update { it.copy(isLoading = false, packs = packs) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "加载图包失败"
                    )
                }
            }
        }
    }

    fun onPackClicked(pack: PackItem, onNavigate: (AppRoute) -> Unit) {
        when (pack) {
            is DirectoryPack -> {
                onNavigate(AppRoute.ThumbnailGrid(packId = pack.id, title = pack.name))
            }
            is ZipPack -> {
                if (!pack.isEncrypted) {
                    onNavigate(AppRoute.ThumbnailGrid(packId = pack.id, title = pack.name))
                } else {
                    // Check if already authenticated in this session
                    if (passwordStore.hasPassword(pack.id)) {
                        onNavigate(AppRoute.ThumbnailGrid(packId = pack.id, title = pack.name))
                    } else {
                        // Open password dialog
                        _uiState.update {
                            it.copy(
                                showPasswordDialog = true,
                                targetZipPack = pack,
                                passwordError = null,
                                isVerifyingPassword = false
                            )
                        }
                    }
                }
            }
        }
    }

    fun dismissPasswordDialog() {
        _uiState.update {
            it.copy(
                showPasswordDialog = false,
                targetZipPack = null,
                passwordError = null,
                isVerifyingPassword = false
            )
        }
    }

    fun verifyAndUnlockPassword(
        password: String,
        onSuccess: () -> Unit,
        onFailureHaptic: () -> Unit
    ) {
        val targetPack = _uiState.value.targetZipPack ?: return
        val targetFile = targetPack.directPath?.let { File(it) }
            ?: safManager.resolveDirectFile(Uri.parse(targetPack.uriString))

        if (targetFile == null || !targetFile.exists()) {
            _uiState.update { it.copy(passwordError = "找不到对应的压缩包文件") }
            onFailureHaptic()
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isVerifyingPassword = true, passwordError = null) }
            val isValid = withContext(Dispatchers.IO) {
                zipArchiveManager.verifyPassword(targetFile, password)
            }

            if (isValid) {
                // Cache password in memory session pool
                passwordStore.set(targetPack.id, password)
                _uiState.update {
                    it.copy(
                        showPasswordDialog = false,
                        targetZipPack = null,
                        isVerifyingPassword = false,
                        passwordError = null
                    )
                }
                onSuccess()
            } else {
                _uiState.update {
                    it.copy(
                        isVerifyingPassword = false,
                        passwordError = "密码错误或解密失败，请重试"
                    )
                }
                onFailureHaptic()
            }
        }
    }
}
