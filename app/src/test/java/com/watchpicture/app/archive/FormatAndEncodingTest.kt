package com.watchpicture.app.archive

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset

class FormatAndEncodingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val gbkCharset = Charset.forName("GBK")
    private val testBytes = byteArrayOf(10, 20, 30, 40)

    @Test
    fun `isImageFile supports extended image formats including jfif and tiff and svg`() {
        assertTrue(ZipArchiveManager.isImageFile("photo.jfif"))
        assertTrue(ZipArchiveManager.isImageFile("photo.pjpeg"))
        assertTrue(ZipArchiveManager.isImageFile("photo.pjp"))
        assertTrue(ZipArchiveManager.isImageFile("scan.tiff"))
        assertTrue(ZipArchiveManager.isImageFile("scan.tif"))
        assertTrue(ZipArchiveManager.isImageFile("vector.svg"))
        assertTrue(ZipArchiveManager.isImageFile("icon.ico"))
        assertTrue(ZipArchiveManager.isImageFile("normal.jpg"))
        assertTrue(ZipArchiveManager.isImageFile("normal.png"))
        assertFalse(ZipArchiveManager.isImageFile("document.pdf"))
        assertFalse(ZipArchiveManager.isImageFile("program.exe"))
    }

    @Test
    fun `ArchiveFileResolver allows cb7 format`() {
        assertFalse("cb7 must not be disallowed", ArchiveFileResolver.isDisallowedExtension("manga.cb7"))
    }

    @Test
    fun `isVideoFile recognises playable video containers and rejects images`() {
        assertTrue(ZipArchiveManager.isVideoFile("clip.MP4"))
        assertTrue(ZipArchiveManager.isVideoFile("clip.m4v"))
        assertTrue(ZipArchiveManager.isVideoFile("clip.mkv"))
        assertTrue(ZipArchiveManager.isVideoFile("clip.webm"))
        assertTrue(ZipArchiveManager.isVideoFile("clip.mov"))
        assertTrue(ZipArchiveManager.isVideoFile("clip.avi"))
        assertTrue(ZipArchiveManager.isVideoFile("clip.3gp"))
        assertFalse(ZipArchiveManager.isVideoFile("page.jpg"))
        assertFalse(ZipArchiveManager.isVideoFile("archive.zip"))
    }

    @Test
    fun `isMediaFile lists both images and videos as browsable pack entries`() {
        assertTrue("图片应作为图包条目", ZipArchiveManager.isMediaFile("page_01.jpg"))
        assertTrue("视频应作为图包条目", ZipArchiveManager.isMediaFile("clip_01.mp4"))
        assertTrue("视频应作为图包条目", ZipArchiveManager.isMediaFile("clip_02.MKV"))
        assertFalse("非媒体文件不应作为图包条目", ZipArchiveManager.isMediaFile("readme.txt"))
        assertFalse("压缩包本身不应作为条目", ZipArchiveManager.isMediaFile("inner.zip"))
    }

    @Test
    fun `ZipArchiveManager automatically recovers GBK chinese entries without replacement character`() {
        val zipFile = tempFolder.newFile("chinese_test.zip")
        ZipFile(zipFile).use { zip ->
            zip.charset = gbkCharset
            val p = ZipParameters().apply {
                fileNameInZip = "漫画目录/第01卷_封面.jpg"
            }
            zip.addStream(ByteArrayInputStream(testBytes), p)
        }

        val manager = ZipArchiveManager()
        val entries = manager.getImageEntries(zipFile)
        assertEquals(1, entries.size)
        // Must contain the actual Chinese character, must NOT contain Unicode replacement character \uFFFD
        assertFalse("Entry name must not contain replacement character", entries[0].name.contains("\uFFFD"))
        assertTrue("Entry name must preserve Chinese characters", entries[0].name.contains("第01卷_封面"))

        val stream = manager.getEntryInputStream(zipFile, entries[0].name)
        val readBytes = stream.use { it.readBytes() }
        assertEquals(testBytes.size, readBytes.size)
    }
}
