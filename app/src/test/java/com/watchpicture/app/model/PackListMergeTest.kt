package com.watchpicture.app.model

import com.watchpicture.app.ui.viewmodel.PackListUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackListMergeTest {

    private fun createZipPack(id: String, name: String): ZipPack {
        return ZipPack(
            id = id,
            name = name,
            uriString = "file://$id",
            directPath = id,
            itemCount = 10,
            isEncrypted = false,
            fileSize = 1024L,
            lastModified = System.currentTimeMillis(),
            coverImage = null,
            isCbz = false
        )
    }

    private fun createDirPack(id: String, name: String): DirectoryPack {
        return DirectoryPack(
            id = id,
            name = name,
            uriString = "file://$id",
            directPath = id,
            itemCount = 5,
            fileSize = 2048L,
            lastModified = System.currentTimeMillis(),
            coverImage = null
        )
    }

    @Test
    fun `merges standalone and scanned packs with deduplication and prioritization`() {
        val standalone1 = createZipPack("/storage/pack_external_1.zip", "External 1")
        val standalone2 = createZipPack("/storage/pack_common.zip", "Common (External)")
        val scanned1 = createZipPack("/storage/pack_common.zip", "Common (Scanned)")
        val scanned2 = createDirPack("/storage/folder_scanned", "Folder Scanned")

        val state = PackListUiState(
            scannedPacks = listOf(scanned1, scanned2),
            standalonePacks = listOf(standalone1, standalone2)
        )

        val merged = state.packs
        // Should have 3 unique packs: external 1, common, folder scanned
        assertEquals(3, merged.size)
        assertEquals("/storage/pack_external_1.zip", merged[0].id)
        assertEquals("/storage/pack_common.zip", merged[1].id)
        assertEquals("/storage/folder_scanned", merged[2].id)

        // Standalone version takes priority for the common pack
        assertEquals("Common (External)", merged[1].name)
    }

    @Test
    fun `archive imported by Uri and also found by folder scan is listed only once`() {
        val contentUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTurrit" +
            "/document/primary%3ADownload%2FTurrit%2F%E6%98%9F%E6%BE%9C%2074P7V.7z"

        // 根目录扫描得到的条目：走裸文件路径，id/uriString 都是真实路径
        val scanned = ZipPack(
            id = "/storage/emulated/0/Download/Turrit/星澜 74P7V.7z",
            name = "星澜 74P7V",
            uriString = "file:///storage/emulated/0/Download/Turrit/%E6%98%9F%E6%BE%9C%2074P7V.7z",
            directPath = "/storage/emulated/0/Download/Turrit/星澜 74P7V.7z",
            itemCount = 0,
            isEncrypted = false,
            fileSize = 1402497106L,
            lastModified = 1L,
            coverImage = null,
            isCbz = false,
            is7z = true
        )
        // 手动导入的同一个归档：id/directPath 指向内部落盘副本，uriString 仍是同一条文档 Uri
        val imported = ZipPack(
            id = "/data/user/0/com.watchpicture.app/cache/opened_archives/abc123_星澜 74P7V.7z",
            name = "星澜 74P7V",
            uriString = contentUri,
            directPath = "/data/user/0/com.watchpicture.app/cache/opened_archives/abc123_星澜 74P7V.7z",
            itemCount = 74,
            isEncrypted = true,
            fileSize = 1402497106L,
            lastModified = 2L,
            coverImage = null,
            isCbz = false,
            is7z = true
        )

        val state = PackListUiState(
            standalonePacks = listOf(imported),
            scannedPacks = listOf(scanned)
        )

        val merged = state.packs
        assertEquals("同一归档不应同时以扫描项与导入项出现两次", 1, merged.size)
        assertEquals("应保留条目数已解析的导入项", 74, merged.first().itemCount)
    }

    @Test
    fun `returns standalone packs even when root folder is not set`() {
        val standalone = createZipPack("/storage/single.zip", "Single")
        val state = PackListUiState(
            rootUri = null,
            scannedPacks = emptyList(),
            standalonePacks = listOf(standalone)
        )

        assertEquals(1, state.packs.size)
        assertEquals("Single", state.packs.first().name)
        assertTrue(state.displayedPacks.isNotEmpty())
    }

    @Test
    fun `pack removal reflects in merged and displayed packs`() {
        val p1 = createZipPack("/storage/p1.zip", "Pack 1")
        val p2 = createZipPack("/storage/p2.zip", "Pack 2")
        val state = PackListUiState(
            standalonePacks = listOf(p1, p2)
        )
        assertEquals(2, state.packs.size)

        val updatedState = state.copy(
            standalonePacks = state.standalonePacks.filter { it.id != p1.id }
        )
        assertEquals(1, updatedState.packs.size)
        assertEquals("/storage/p2.zip", updatedState.packs.first().id)
    }

    @Test
    fun `displayedPacks and packs are memoized across repeated accesses`() {
        val p1 = createZipPack("/storage/p1.zip", "Pack 1")
        val state = PackListUiState(standalonePacks = listOf(p1))

        val packs1 = state.packs
        val packs2 = state.packs
        org.junit.Assert.assertSame("packs should return identical memoized reference", packs1, packs2)

        val disp1 = state.displayedPacks
        val disp2 = state.displayedPacks
        org.junit.Assert.assertSame("displayedPacks should return identical memoized reference for 120Hz recomposition", disp1, disp2)

        val stateFiltered = state.copy(searchQuery = "nonexistent")
        val dispFiltered = stateFiltered.displayedPacks
        assertTrue("Filtered packs should be empty", dispFiltered.isEmpty())
    }
}
