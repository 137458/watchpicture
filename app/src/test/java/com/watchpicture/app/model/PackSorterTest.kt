package com.watchpicture.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PackSorterTest {

    private val packA = DirectoryPack(
        id = "1",
        name = "Comic 10",
        uriString = "uri1",
        directPath = "/p/1",
        itemCount = 50,
        fileSize = 1024L * 1024L,
        lastModified = 1000L
    )
    private val packB = DirectoryPack(
        id = "2",
        name = "Comic 2",
        uriString = "uri2",
        directPath = "/p/2",
        itemCount = 10,
        fileSize = 5 * 1024L * 1024L,
        lastModified = 3000L
    )
    private val packC = ZipPack(
        id = "3",
        name = "Artbook 1",
        uriString = "uri3",
        directPath = "/p/3",
        itemCount = 100,
        isEncrypted = false,
        fileSize = 3 * 1024L * 1024L,
        lastModified = 2000L
    )

    private val list = listOf(packA, packB, packC)

    @Test
    fun `sorts naturally by name ascending`() {
        val sorted = PackSorter.sort(list, SortOption.NAME_ASC)
        assertEquals(listOf(packC, packB, packA), sorted)
    }

    @Test
    fun `sorts naturally by name descending`() {
        val sorted = PackSorter.sort(list, SortOption.NAME_DESC)
        assertEquals(listOf(packA, packB, packC), sorted)
    }

    @Test
    fun `sorts by last modified descending`() {
        val sorted = PackSorter.sort(list, SortOption.TIME_DESC)
        assertEquals(listOf(packB, packC, packA), sorted)
    }

    @Test
    fun `sorts by last modified ascending`() {
        val sorted = PackSorter.sort(list, SortOption.TIME_ASC)
        assertEquals(listOf(packA, packC, packB), sorted)
    }

    @Test
    fun `sorts by file size descending`() {
        val sorted = PackSorter.sort(list, SortOption.SIZE_DESC)
        assertEquals(listOf(packB, packC, packA), sorted)
    }

    @Test
    fun `sorts by item count descending`() {
        val sorted = PackSorter.sort(list, SortOption.COUNT_DESC)
        assertEquals(listOf(packC, packA, packB), sorted)
    }

    @Test
    fun `filters pack by keyword case-insensitively`() {
        val filtered = PackFilter.filter(list, "art")
        assertEquals(listOf(packC), filtered)

        val filteredComic = PackFilter.filter(list, "COMIC")
        assertEquals(listOf(packA, packB), filteredComic)

        val emptyQuery = PackFilter.filter(list, "   ")
        assertEquals(list, emptyQuery)
    }
}
