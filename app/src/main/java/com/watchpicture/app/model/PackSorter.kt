package com.watchpicture.app.model

import com.watchpicture.app.archive.NaturalOrderComparator

enum class SortOption {
    NAME_ASC,
    NAME_DESC,
    TIME_DESC,
    TIME_ASC,
    SIZE_DESC,
    COUNT_DESC
}

object PackSorter {
    private val naturalOrderComparator = NaturalOrderComparator()

    fun sort(packs: List<PackItem>, option: SortOption): List<PackItem> {
        return when (option) {
            SortOption.NAME_ASC -> packs.sortedWith { a, b ->
                naturalOrderComparator.compare(a.name, b.name)
            }
            SortOption.NAME_DESC -> packs.sortedWith { a, b ->
                naturalOrderComparator.compare(b.name, a.name)
            }
            SortOption.TIME_DESC -> packs.sortedWith { a, b ->
                b.lastModified.compareTo(a.lastModified)
            }
            SortOption.TIME_ASC -> packs.sortedWith { a, b ->
                a.lastModified.compareTo(b.lastModified)
            }
            SortOption.SIZE_DESC -> packs.sortedWith { a, b ->
                b.fileSize.compareTo(a.fileSize)
            }
            SortOption.COUNT_DESC -> packs.sortedWith { a, b ->
                b.itemCount.compareTo(a.itemCount)
            }
        }
    }
}

object PackFilter {
    fun filter(packs: List<PackItem>, query: String): List<PackItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return packs
        }
        return packs.filter { it.name.contains(trimmed, ignoreCase = true) }
    }
}
