package com.watchpicture.app.archive

import java.io.File

/**
 * Recursively inspects folder hierarchies to collect image files,
 * ensuring nested chapter folders and wrapper directories are properly detected.
 */
object DeepFolderImageResolver {

    private val comparator = NaturalOrderComparator()

    fun collectImages(dir: File): List<File> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val results = mutableListOf<File>()
        traverse(dir, dir, results)

        return results.sortedWith { a, b ->
            val relA = a.relativeTo(dir).path.replace('\\', '/')
            val relB = b.relativeTo(dir).path.replace('\\', '/')
            comparator.compare(relA, relB)
        }
    }

    private fun traverse(rootDir: File, currentDir: File, outList: MutableList<File>) {
        val files = currentDir.listFiles() ?: return

        for (file in files) {
            val name = file.name
            if (name.startsWith(".") || name.equals("__MACOSX", ignoreCase = true)) {
                continue
            }
            if (ZipArchiveManager.isIgnoredFile(name)) {
                continue
            }

            if (file.isDirectory) {
                traverse(rootDir, file, outList)
            } else if (file.isFile && ZipArchiveManager.isImageFile(name)) {
                outList.add(file)
            }
        }
    }
}
