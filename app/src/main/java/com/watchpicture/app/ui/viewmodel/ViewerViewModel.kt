package com.watchpicture.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchpicture.app.WatchPictureApp
import com.watchpicture.app.archive.NaturalOrderComparator
import com.watchpicture.app.archive.ZipArchiveManager
import com.watchpicture.app.model.PackImage
import com.watchpicture.app.storage.SafManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ViewerUiState(
    val isLoading: Boolean = true,
    val images: List<PackImage> = emptyList(),
    val errorMessage: String? = null
)

class ViewerViewModel(application: Application = WatchPictureApp.instance) : AndroidViewModel(application) {

    constructor() : this(WatchPictureApp.instance)

    private val app = application as WatchPictureApp
    private val zipArchiveManager = app.zipArchiveManager
    private val passwordStore = app.sessionPasswordStore
    private val archiveFileResolver = app.archiveFileResolver
    private val safManager = SafManager(zipArchiveManager, passwordStore)
    private val naturalOrderComparator = NaturalOrderComparator()

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    fun loadImages(packId: String) {
        val cached = cachedImageList(packId)
        if (cached != null && cached.isNotEmpty()) {
            com.watchpicture.app.util.AppLog.i("PackImages", "Instant 0ms cache hit for $packId (${cached.size} images)")
            _uiState.update { it.copy(isLoading = false, images = cached, errorMessage = null) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val t0 = System.currentTimeMillis()
            try {
                val images = withContext(Dispatchers.IO) {
                    resolvePackImages(packId)
                }
                cacheImageList(packId, images)
                val elapsed = System.currentTimeMillis() - t0
                com.watchpicture.app.util.AppLog.i("PackImages", "Resolved ${images.size} entries for $packId in ${elapsed}ms")
                _uiState.update { it.copy(isLoading = false, images = images) }
            } catch (e: Exception) {
                com.watchpicture.app.util.AppLog.e("PackImages", "Failed to load pack images for $packId", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "加载图包图片失败"
                    )
                }
            }
        }
    }

    private suspend fun resolvePackImages(packId: String): List<PackImage> {
        val file = File(packId)
        if (file.exists()) {
            if (file.isDirectory) {
                val imageFiles = com.watchpicture.app.archive.DeepFolderImageResolver.collectImages(file)

                return imageFiles.map { img ->
                    val relPath = img.relativeTo(file).path.replace('\\', '/')
                    PackImage(
                        packId = packId,
                        entryPath = relPath,
                        displayName = img.name,
                        isEncrypted = false,
                        directFilePath = img.absolutePath
                    )
                }
            } else if (file.isFile) {
                val password = passwordStore.get(packId)
                    ?: passwordStore.get(file.absolutePath)
                val entries = zipArchiveManager.getImageEntries(file, password)
                return entries.map { entry ->
                    PackImage(
                        packId = packId,
                        entryPath = entry.name,
                        displayName = entry.name.substringAfterLast('/'),
                        isEncrypted = entry.isEncrypted,
                        directFilePath = file.absolutePath
                    )
                }
            }
        }

        // Fallback for SAF URI strings
        val uri = Uri.parse(packId)
        val directFile = safManager.resolveDirectFile(uri)
        if (directFile != null && directFile.exists()) {
            passwordStore.get(packId)?.let { pwd ->
                passwordStore.set(packId, pwd, aliases = listOf(directFile.absolutePath))
            }
            return resolvePackImages(directFile.absolutePath)
        }

        val docFile = DocumentFile.fromSingleUri(app, uri)
            ?: DocumentFile.fromTreeUri(app, uri)

        if (docFile != null && docFile.isDirectory) {
            val list = docFile.listFiles()
            return list.filter { it.isFile && ZipArchiveManager.isImageFile(it.name ?: "") }
                .sortedWith { a, b -> naturalOrderComparator.compare(a.name ?: "", b.name ?: "") }
                .map { doc ->
                    PackImage(
                        packId = packId,
                        entryPath = doc.name ?: "",
                        displayName = doc.name ?: "",
                        isEncrypted = false,
                        fileUri = doc.uri.toString()
                    )
                }
        }

        // Resolves single archive files (content://...) via ArchiveFileResolver
        val resolvedPack = archiveFileResolver.resolve(app, uri)
        if (resolvedPack != null && resolvedPack.directPath != null) {
            passwordStore.get(packId)?.let { pwd ->
                passwordStore.set(
                    resolvedPack.id,
                    pwd,
                    aliases = listOfNotNull(resolvedPack.directPath, packId)
                )
            }
            return resolvePackImages(resolvedPack.directPath)
        }

        return emptyList()
    }

    val preferencesRepository = app.preferencesRepository
    val readingMode = preferencesRepository.readingModeFlow

    fun toggleReadingMode(current: com.watchpicture.app.storage.ReadingMode) {
        viewModelScope.launch {
            val next = if (current == com.watchpicture.app.storage.ReadingMode.LTR) {
                com.watchpicture.app.storage.ReadingMode.RTL
            } else {
                com.watchpicture.app.storage.ReadingMode.LTR
            }
            preferencesRepository.saveReadingMode(next)
        }
    }

    /**
     * Clears password from memory for this pack when user explicitly locks or leaves.
     */
    fun clearSessionPassword(packId: String) {
        passwordStore.remove(packId)
        val file = File(packId)
        if (file.exists() && file.isFile) {
            app.archiveExtractionCoordinator.closeSession(file)
        }
    }

    fun lockPack(packId: String) {
        removeCachedImageList(packId)
        passwordStore.markExplicitlyLocked(packId)
        val file = File(packId)
        if (file.exists() && file.isFile) {
            app.archiveExtractionCoordinator.closeSession(file)
        }
    }

    companion object {
        // Shared, process-wide LRU for resolved image lists. The thumbnail grid and the fullscreen
        // viewer each create their own ViewerViewModel, so an instance field would make both
        // re-scan the same pack; sharing prevents that double resolution.
        private const val IMAGE_CACHE_MAX = 16
        private val imageCacheLock = Any()
        private val imageCache = java.util.LinkedHashMap<String, List<PackImage>>(IMAGE_CACHE_MAX, 0.75f, true)

        private fun cachedImageList(packId: String): List<PackImage>? =
            synchronized(imageCacheLock) { imageCache[packId] }

        private fun cacheImageList(packId: String, images: List<PackImage>) {
            synchronized(imageCacheLock) {
                imageCache[packId] = images
                // accessOrder=true -> keys iterate from least- to most-recently used.
                while (imageCache.size > IMAGE_CACHE_MAX) {
                    imageCache.remove(imageCache.keys.first())
                }
            }
        }

        private fun removeCachedImageList(packId: String) {
            synchronized(imageCacheLock) { imageCache.remove(packId) }
        }
    }
}
