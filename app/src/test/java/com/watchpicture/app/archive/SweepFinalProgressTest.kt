package com.watchpicture.app.archive

import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 扫描收尾必须把进度推到 (total, total)：缺失/失败条目不计 completed，
 * 但进度分母以整包条目数为准，收尾不补齐会让进度芯片永远卡在 N/M。
 */
class SweepFinalProgressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun entryBytes(index: Int): ByteArray =
        ("final-page-" + index + "-" + (0 until 24).joinToString("") { "-" }).toByteArray()

    private fun newCoordinator(cacheDir: String): ArchiveExtractionCoordinator = ArchiveExtractionCoordinator(
        ZipArchiveManager(),
        ArchiveDiskCache(tempFolder.newFolder(cacheDir)),
        PowerThermalManager()
    )

    @Test
    fun `zip 含缺失条目时扫描结束仍报告完整进度`() = runBlocking {
        val zip = tempFolder.newFile("pack-${System.nanoTime()}.zip")
        ZipFile(zip).use { pack ->
            for ((index, name) in listOf("img/a.jpg", "img/b.jpg").withIndex()) {
                val params = ZipParameters()
                params.fileNameInZip = name
                pack.addStream(ByteArrayInputStream(entryBytes(index)), params)
            }
        }
        val thumbCache = ThumbnailDiskCache(
            tempFolder.newFolder("thumb_final_zip"),
            maxSizeBytes = 64L * 1024 * 1024
        )
        val names = listOf("img/a.jpg", "img/b.jpg", "img/missing.jpg")

        var lastCompleted = -1
        var lastTotal = -1
        newCoordinator("archive_final_zip").startBatchThumbnailSweep(
            file = zip,
            entryNames = names,
            targetSizePx = 360,
            password = null,
            thumbnailDiskCache = thumbCache,
            onProgress = { completed, total ->
                lastCompleted = completed
                lastTotal = total
            }
        )

        assertEquals(names.size, lastTotal)
        assertEquals("缺失条目不得让最终进度卡在 N/M", names.size, lastCompleted)
    }

    @Test
    fun `文件夹包缺失条目时扫描结束仍报告完整进度`() = runBlocking {
        val dir = tempFolder.newFolder("folder_final")
        File(dir, "a.jpg").writeBytes(entryBytes(0))
        val thumbCache = ThumbnailDiskCache(
            tempFolder.newFolder("thumb_final_folder"),
            maxSizeBytes = 64L * 1024 * 1024
        )
        val names = listOf("a.jpg", "gone/b.jpg", "gone/c.jpg")

        var lastCompleted = -1
        var lastTotal = -1
        newCoordinator("archive_final_folder").startBatchThumbnailSweep(
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

        assertEquals(names.size, lastTotal)
        assertEquals(names.size, lastCompleted)
    }
}
