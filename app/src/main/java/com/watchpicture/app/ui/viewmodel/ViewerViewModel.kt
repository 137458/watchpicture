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
                val imageFiles = com.watchpicture.app.archive.DeepFolderImageResolver.collectMedia(file)

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
                val entries = zipArchiveManager.getMediaEntries(file, password)
                if (entries.isEmpty()) {
                    // 空网格的诊断入口：区分「密码没带上」与「归档本身读不出条目」
                    com.watchpicture.app.util.AppLog.e(
                        "PackImages",
                        "归档条目为空: file=${file.name.take(48)} 体积=${file.length()} " +
                            "有密码=${password != null} 判定加密=${zipArchiveManager.isEncrypted(file)} " +
                            "packId前缀=${packId.take(24)}"
                    )
                }
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
        // 只有真正可读的裸路径才能直接解析；权限受限（如目录 0770）时必须放行到
        // 下面的 provider 落盘通路（ArchiveFileResolver），否则 zip4j 以空条目收场。
        if (directFile != null && com.watchpicture.app.archive.ArchiveFileResolver.isDirectlyReadable(directFile)) {
            passwordStore.get(packId)?.let { pwd ->
                passwordStore.set(packId, pwd, aliases = listOf(directFile.absolutePath))
            }
            return resolvePackImages(directFile.absolutePath)
        }

        val docFile = DocumentFile.fromSingleUri(app, uri)
            ?: DocumentFile.fromTreeUri(app, uri)

        if (docFile != null && docFile.isDirectory) {
            val list = docFile.listFiles()
            return list.filter { it.isFile && ZipArchiveManager.isMediaFile(it.name ?: "") }
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
        clearBrowseState(packId)
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
        private const val BROWSE_STATE_MAX = 64
        private val imageCacheLock = Any()
        private val imageCache = java.util.LinkedHashMap<String, List<PackImage>>(IMAGE_CACHE_MAX, 0.75f, true)

        private val browseStateLock = Any()
        private val browseStateCache = java.util.LinkedHashMap<String, PackBrowseState>(BROWSE_STATE_MAX, 0.75f, true)
        private val _browseStatesFlow = MutableStateFlow<Map<String, PackBrowseState>>(emptyMap())
        val browseStatesFlow: StateFlow<Map<String, PackBrowseState>> = _browseStatesFlow.asStateFlow()

        fun getCachedImages(packId: String): List<PackImage>? = cachedImageList(packId)

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

        fun getBrowseState(packId: String): PackBrowseState =
            synchronized(browseStateLock) { browseStateCache[packId] ?: PackBrowseState() }

        fun saveScrollPosition(packId: String, firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
            if (packId.isEmpty()) return
            synchronized(browseStateLock) {
                val current = browseStateCache[packId] ?: PackBrowseState()
                val updated = current.copy(
                    firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
                    firstVisibleItemScrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0)
                )
                if (updated != current) {
                    browseStateCache[packId] = updated
                    trimBrowseStateCacheLocked()
                    _browseStatesFlow.value = HashMap(browseStateCache)
                }
            }
        }

        fun saveLastViewedIndex(packId: String, index: Int) {
            if (packId.isEmpty() || index < 0) return
            synchronized(browseStateLock) {
                val current = browseStateCache[packId] ?: PackBrowseState()
                val updated = current.copy(lastViewedIndex = index)
                if (updated != current) {
                    browseStateCache[packId] = updated
                    trimBrowseStateCacheLocked()
                    _browseStatesFlow.value = HashMap(browseStateCache)
                }
            }
        }

        private fun clearBrowseState(packId: String) {
            synchronized(browseStateLock) {
                if (browseStateCache.remove(packId) != null) {
                    _browseStatesFlow.value = HashMap(browseStateCache)
                }
            }
        }

        private fun trimBrowseStateCacheLocked() {
            while (browseStateCache.size > BROWSE_STATE_MAX) {
                browseStateCache.remove(browseStateCache.keys.first())
            }
        }

        /**
         * 判断从全屏阅读器返回缩略图网格时，是否需要将网格滚动到 [lastViewedIndex]。
         * - 若 [lastViewedIndex] 仍在当前可视区域 [visibleItemIndices] 内，返回 false，保持精确像素滚动偏移量不跳动；
         * - 若用户在全屏阅读器中翻页超出了当前可视区域，返回 true，使网格自动定位到最新阅读的图片。
         */
        fun shouldScrollToLastViewed(
            lastViewedIndex: Int,
            visibleItemIndices: List<Int>,
            totalItems: Int
        ): Boolean {
            if (lastViewedIndex < 0 || lastViewedIndex >= totalItems) return false
            if (visibleItemIndices.isEmpty()) return lastViewedIndex > 0
            return lastViewedIndex !in visibleItemIndices
        }

        fun clearBrowseStateForTest() {
            synchronized(browseStateLock) {
                browseStateCache.clear()
                _browseStatesFlow.value = emptyMap()
            }
        }
    }
}

data class PackBrowseState(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val lastViewedIndex: Int = -1
)
