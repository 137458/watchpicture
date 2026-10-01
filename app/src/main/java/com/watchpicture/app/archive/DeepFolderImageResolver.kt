package com.watchpicture.app.archive

import java.io.File

/**
 * Recursively inspects folder hierarchies to collect browsable media files (images and videos),
 * ensuring nested chapter folders and wrapper directories are properly detected.
 */
object DeepFolderImageResolver {

    private val comparator = NaturalOrderComparator()

    /** 目录遍历深度上限:拦住病态深树,正常图包章节嵌套远低于此。 */
    private const val MAX_TRAVERSE_DEPTH = 50

    fun collectMedia(dir: File): List<File> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val results = mutableListOf<File>()
        traverse(dir, dir, results, 0, HashSet())

        return results.sortedWith { a, b ->
            val relA = a.relativeTo(dir).path.replace('\\', '/')
            val relB = b.relativeTo(dir).path.replace('\\', '/')
            comparator.compare(relA, relB)
        }
    }

    private fun traverse(
        rootDir: File,
        currentDir: File,
        outList: MutableList<File>,
        depth: Int,
        visitedDirs: MutableSet<String>
    ) {
        if (depth > MAX_TRAVERSE_DEPTH) return
        // 符号链接环守卫:同一规范路径二次进入即剪枝
        val canonical = try { currentDir.canonicalPath } catch (_: Throwable) { currentDir.absolutePath }
        if (!visitedDirs.add(canonical)) return

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
                traverse(rootDir, file, outList, depth + 1, visitedDirs)
            } else if (file.isFile && ZipArchiveManager.isMediaFile(name)) {
                outList.add(file)
            }
        }
    }
}
