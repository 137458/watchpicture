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
}
