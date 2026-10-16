package com.watchpicture.app.archive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 文件夹图包全量扫描：条目就是磁盘上的真实文件，必须走直接降采样分支，
 * 且进度以整包条目数为分母。
 */
class FolderPackSweepTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun entryBytes(index: Int): ByteArray =
        ("folder-page-" + index + "-" + (0 until 24).joinToString("") { "-" }).toByteArray()

    @Test
    fun `文件夹图包全量扫描缓存全部条目并报告完整进度`() = runBlocking {
        val dir = tempFolder.newFolder("folder_pack")
        val names = (0 until 6).map { "sub/page%02d.jpg".format(it) }
        names.forEachIndexed { index, name ->
            File(dir, name).apply { parentFile?.mkdirs() }.writeBytes(entryBytes(index))
        }

        val thumbCache = ThumbnailDiskCache(
            tempFolder.newFolder("thumb_cache_folder"),
            maxSizeBytes = 64L * 1024 * 1024
        )
        val coordinator = ArchiveExtractionCoordinator(
            ZipArchiveManager(),
            ArchiveDiskCache(tempFolder.newFolder("archive_cache_folder")),
            PowerThermalManager()
        )

        var lastCompleted = -1
        var lastTotal = -1
        coordinator.startBatchThumbnailSweep(
            file = dir,
            entryNames = names,
            targetSizePx = 360,
            password = null,
            thumbnailDiskCache = thumbCache,
            onProgress = { completed, total ->
                lastCompleted = completed
                lastTotal = total
            }
        )

        assertEquals("进度分母应为整包条目数", names.size, lastTotal)
        assertEquals("应全部完成", names.size, lastCompleted)
        names.forEachIndexed { index, name ->
            val thumb = thumbCache.get(dir, name, 360, null)
            assertNotNull("缺少缩略图: " + name, thumb)
            assertArrayEquals(entryBytes(index), thumb!!.readBytes())
        }
    }
}
