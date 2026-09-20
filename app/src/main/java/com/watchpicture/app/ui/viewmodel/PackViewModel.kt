package com.watchpicture.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.model.DirectoryPack
import com.watchpicture.app.model.PackFilter
import com.watchpicture.app.model.PackImage
import com.watchpicture.app.model.PackItem
import com.watchpicture.app.model.PackSorter
import com.watchpicture.app.model.SortOption
import com.watchpicture.app.model.ZipPack
import com.watchpicture.app.navigation.AppRoute
import com.watchpicture.app.storage.SafManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PackListUiState(
    val isLoading: Boolean = false,
    val rootUri: Uri? = null,
    val scannedPacks: List<PackItem> = emptyList(),
    val standalonePacks: List<ZipPack> = emptyList(),
    val errorMessage: String? = null,
    // Search & Sort state
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val sortOption: SortOption = SortOption.NAME_ASC,
    // Password dialog state
    val showPasswordDialog: Boolean = false,
    val targetZipPack: ZipPack? = null,
    val passwordError: String? = null,
    val isVerifyingPassword: Boolean = false
) {
    val packs: List<PackItem>
        get() {
            val seen = mutableSetOf<String>()
            val combined = mutableListOf<PackItem>()
            for (p in standalonePacks) {
                if (seen.add(p.id)) combined.add(p)
            }
            for (p in scannedPacks) {
                if (seen.add(p.id)) combined.add(p)
            }
            return combined
        }

    val displayedPacks: List<PackItem>
        get() {
            val filtered = PackFilter.filter(packs, searchQuery)
            return PackSorter.sort(filtered, sortOption)
        }
}

class PackViewModel(application: Application = WatchPictureApp.instance) : AndroidViewModel(application) {

    constructor() : this(WatchPictureApp.instance)

    private val app = application as WatchPictureApp
    private val zipArchiveManager = app.zipArchiveManager
    private val passwordStore = app.sessionPasswordStore
    private val preferencesRepository = app.preferencesRepository
    private val safManager = SafManager(zipArchiveManager, passwordStore)
    private val archiveFileResolver = app.archiveFileResolver

    private val _uiState = MutableStateFlow(PackListUiState())
    val uiState: StateFlow<PackListUiState> = _uiState.asStateFlow()

    init {
        // Restore preferences on startup
        viewModelScope.launch {
            val savedSortOption = preferencesRepository.sortOptionFlow.first()
            _uiState.update { it.copy(sortOption = savedSortOption) }

            val savedUriString = preferencesRepository.lastRootUriFlow.first()
            if (savedUriString != null) {
                try {
                    val uri = Uri.parse(savedUriString)
                    _uiState.update { it.copy(rootUri = uri) }
                    loadPacks(uri)
                } catch (_: Exception) {
                    // Ignore corrupted uri
                }
            }
        }
    }

    fun onRootFolderSelected(uri: Uri) {
        safManager.takePersistablePermission(app, uri)
        _uiState.update { it.copy(rootUri = uri) }
        viewModelScope.launch {
            preferencesRepository.saveLastRootUri(uri.toString())
        }
        loadPacks(uri)
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSearchActive(active: Boolean) {
        _uiState.update {
            it.copy(
                isSearchActive = active,
                searchQuery = if (!active) "" else it.searchQuery
            )
        }
    }

    fun onSortOptionSelected(option: SortOption) {
        _uiState.update { it.copy(sortOption = option) }
        viewModelScope.launch {
            preferencesRepository.saveSortOption(option)
        }
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
                _uiState.update { it.copy(isLoading = false, scannedPacks = packs) }
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

    /**
     * Resolves a single archive (ZIP / CBZ) from Uri, stores it in standalone packs,
     * and immediately navigates to thumbnail preview or prompts for decryption.
     */
    fun openSingleArchive(uri: Uri, onNavigate: (AppRoute) -> Unit) {
        val pathOrName = uri.lastPathSegment.orEmpty()
        if (com.watchpicture.app.archive.ArchiveFileResolver.isDisallowedExtension(pathOrName)) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = "不支持打开 APK 等应用安装包或非图集文件"
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val pack = archiveFileResolver.resolve(app, uri)
            if (pack == null) {
                val isApk = com.watchpicture.app.archive.ArchiveFileResolver.isDisallowedExtension(pathOrName)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (isApk) "不支持打开 APK 等应用安装包或非图集文件" else "无法打开该压缩包：格式不受支持或已损坏"
                    )
                }
                return@launch
            }

            // Stash into standalonePacks (putting newly opened archive at the front)
            _uiState.update { state ->
                val updatedStandalone = listOf(pack) + state.standalonePacks.filter { it.id != pack.id }
                state.copy(isLoading = false, standalonePacks = updatedStandalone)
            }

            // Immediately trigger open & unlock flow
            onPackClicked(pack, onNavigate)
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
                        return
                    }

                    // Attempt auto-unlock using lastUsedPassword
                    val lastPwd = passwordStore.lastUsedPassword
                    val targetFile = pack.directPath?.let { File(it) }
                        ?: safManager.resolveDirectFile(Uri.parse(pack.uriString))

                    if (lastPwd != null && targetFile != null && targetFile.exists()) {
                        viewModelScope.launch {
                            val isValid = withContext(Dispatchers.IO) {
                                zipArchiveManager.verifyPassword(targetFile, lastPwd)
                            }
                            if (isValid) {
                                val aliases = listOfNotNull(
                                    targetFile.absolutePath,
                                    pack.directPath,
                                    pack.uriString
                                ).distinct()
                                passwordStore.set(pack.id, lastPwd, aliases = aliases)
                                onNavigate(AppRoute.ThumbnailGrid(packId = pack.id, title = pack.name))
                                return@launch
                            } else {
                                // Fallback to prompt dialog
                                promptPasswordDialog(pack)
                            }
                        }
                    } else {
                        promptPasswordDialog(pack)
                    }
                }
            }
        }
    }

    private fun promptPasswordDialog(pack: ZipPack) {
        _uiState.update {
            it.copy(
                showPasswordDialog = true,
                targetZipPack = pack,
                passwordError = null,
                isVerifyingPassword = false
            )
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
                // Cache password in memory session pool with all known aliases
                val aliases = listOfNotNull(
                    targetFile.absolutePath,
                    targetPack.directPath,
                    targetPack.uriString
                ).distinct()
                passwordStore.set(targetPack.id, password, aliases = aliases)

                // Update item count and cover after unlocking
                val entries = zipArchiveManager.getImageEntries(targetFile, password)
                val updatedCover = entries.firstOrNull()?.let { entry ->
                    PackImage(
                        packId = targetPack.id,
                        entryPath = entry.name,
                        displayName = entry.name.substringAfterLast('/'),
                        isEncrypted = entry.isEncrypted,
                        directFilePath = targetFile.absolutePath,
                        fileUri = targetPack.uriString
                    )
                }
                val updatedPack = targetPack.copy(
                    itemCount = entries.size,
                    coverImage = updatedCover ?: targetPack.coverImage
                )

                _uiState.update { state ->
                    val updatedStandalone = state.standalonePacks.map {
                        if (it.id == updatedPack.id) updatedPack else it
                    }
                    val updatedScanned = state.scannedPacks.map {
                        if (it.id == updatedPack.id) updatedPack else it
                    }
                    state.copy(
                        showPasswordDialog = false,
                        targetZipPack = null,
                        isVerifyingPassword = false,
                        passwordError = null,
                        standalonePacks = updatedStandalone,
                        scannedPacks = updatedScanned
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

    /**
     * Removes a pack from standalone or scanned packs, cleans up cached files if applicable,
     * and revokes any session passwords for this pack.
     */
    fun removePack(pack: PackItem, deleteFile: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. If it's a cached file from ContentResolver, always delete cache file
            val directPath = pack.directPath
            if (directPath != null) {
                val file = File(directPath)
                if (file.exists()) {
                    if (file.parentFile?.name == "opened_archives" || deleteFile) {
                        try {
                            file.delete()
                        } catch (_: Exception) {}
                    }
                }
            }

            // 2. Clear password from session cache
            passwordStore.remove(pack.id)

            // 3. Update UI state
            _uiState.update { state ->
                state.copy(
                    standalonePacks = state.standalonePacks.filter { it.id != pack.id },
                    scannedPacks = state.scannedPacks.filter { it.id != pack.id }
                )
            }
        }
    }
}
