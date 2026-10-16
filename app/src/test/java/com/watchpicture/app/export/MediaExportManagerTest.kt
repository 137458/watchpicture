package com.watchpicture.app.export

import com.watchpicture.app.archive.ZipArchiveManager
import com.watchpicture.app.model.PackImage
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 一键导出：从图包条目定位真实字节来源并写向用户自选目标。
 * JVM 侧以 File 目标覆盖「来源解析 + 字节写入」核心逻辑，SAF Uri 目标仅做薄壳转发。
 */
class MediaExportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun entryBytes(index: Int): ByteArray =
        "export-page-$index-${(0 until 32).joinToString("") { "-" }}".toByteArray()

    private fun createZip(names: List<String>): File {
        val target = tempFolder.newFile("pack-${System.nanoTime()}.zip")
        ZipFile(target).use { zip ->
            for ((index, name) in names.withIndex()) {
                val params = ZipParameters()
                params.fileNameInZip = name
                zip.addStream(ByteArrayInputStream(entryBytes(index)), params)
            }
        }
        return target
    }

    @Test
    fun `文件夹条目直接复制真实文件且字节一致`() {
        val dir = tempFolder.newFolder("folder_pack")
        val source = File(dir, "sub/page.jpg")
        source.parentFile?.mkdirs()
        source.writeBytes(entryBytes(3))

        val destination = File(tempFolder.newFolder("out_folder"), "page.jpg")
        val item = PackImage(
            packId = "pack-folder",
            entryPath = "sub/page.jpg",
            displayName = "page.jpg",
            directFilePath = dir.absolutePath
        )

        val outcome = MediaExportManager.exportToFile(item, sessionPassword = null, destination = destination)

        assertTrue("应导出成功: $outcome", outcome is ExportOutcome.Success)
        assertArrayEquals(entryBytes(3), destination.readBytes())
    }

    @Test
    fun `压缩包条目从归档流式导出且字节一致`() {
        val zip = createZip(listOf("img/page00.jpg", "img/page01.jpg"))
        val destination = File(tempFolder.newFolder("out_zip"), "page01.jpg")
        val item = PackImage(
            packId = "pack-zip",
            entryPath = "img/page01.jpg",
            displayName = "page01.jpg",
            directFilePath = zip.absolutePath
        )

        val outcome = MediaExportManager.exportToFile(item, sessionPassword = null, destination = destination)

        assertTrue("应导出成功: $outcome", outcome is ExportOutcome.Success)
        assertArrayEquals(entryBytes(1), destination.readBytes())
    }

    @Test
    fun `加密压缩包条目在密码正确时导出成功`() {
        val password = "export-pw"
        val target = tempFolder.newFile("enc-${System.nanoTime()}.zip")
        ZipFile(target).use { zip ->
            zip.setPassword(password.toCharArray())
            val params = ZipParameters().apply {
                fileNameInZip = "img/secret.jpg"
                isEncryptFiles = true
                encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
            }
            zip.addStream(ByteArrayInputStream(entryBytes(7)), params)
        }
        val destination = File(tempFolder.newFolder("out_enc"), "secret.jpg")
        val item = PackImage(
            packId = "pack-enc",
            entryPath = "img/secret.jpg",
            displayName = "secret.jpg",
            directFilePath = target.absolutePath,
            isEncrypted = true
        )

        val outcome = MediaExportManager.exportToFile(item, sessionPassword = password, destination = destination)

        assertTrue("应导出成功: $outcome", outcome is ExportOutcome.Success)
        assertArrayEquals(entryBytes(7), destination.readBytes())
    }

    @Test
    fun `未知条目返回失败而不是留下空文件`() {
        val zip = createZip(listOf("img/page00.jpg"))
        val destination = File(tempFolder.newFolder("out_missing"), "missing.jpg")
        val item = PackImage(
            packId = "pack-zip",
            entryPath = "img/does-not-exist.jpg",
            displayName = "does-not-exist.jpg",
            directFilePath = zip.absolutePath
        )

        val outcome = MediaExportManager.exportToFile(item, sessionPassword = null, destination = destination)

        assertTrue("应返回失败: $outcome", outcome is ExportOutcome.Failure)
        assertTrue("失败时不应留下目标文件", !destination.exists() || destination.length() == 0L)
    }

    @Test
    fun `导出命名保留原始扩展名并清洗非法字符`() {
        assertEquals("page.jpg", MediaExportManager.suggestedFileName("gallery/page.jpg"))
        assertEquals("page.jpg", MediaExportManager.suggestedFileName("page.jpg"))
        assertEquals("clip.mp4", MediaExportManager.suggestedFileName("a/b/c/clip.mp4"))
        assertEquals("invalid_name_.jpg", MediaExportManager.suggestedFileName("invalid:\\name*.jpg"))
    }

    @Test
    fun `归档条目路径判定区分文件夹与压缩包`() {
        val archive = createZip(listOf("img/page00.jpg"))
        val archiveItem = PackImage(
            packId = "p",
            entryPath = "img/page00.jpg",
            displayName = "page00.jpg",
            directFilePath = archive.absolutePath
        )
        assertEquals(archive.absolutePath, MediaExportManager.archiveFileOf(archiveItem)?.absolutePath)

        val dir = tempFolder.newFolder("plain")
        val dirItem = PackImage(
            packId = "d",
            entryPath = "page00.jpg",
            displayName = "page00.jpg",
            directFilePath = dir.absolutePath
        )
        assertNull(MediaExportManager.archiveFileOf(dirItem))
    }

    @Test
    fun `同一目标重复导出覆盖且字节正确`() {
        val dir = tempFolder.newFolder("folder_pack2")
        val source = File(dir, "page.jpg")
        source.writeBytes(entryBytes(1))
        val destination = File(tempFolder.newFolder("out_overwrite"), "page.jpg")
        val item = PackImage(
            packId = "pack-folder",
            entryPath = "page.jpg",
            displayName = "page.jpg",
            directFilePath = dir.absolutePath
        )

        MediaExportManager.exportToFile(item, null, destination)
        source.writeBytes(entryBytes(2))
        val outcome = MediaExportManager.exportToFile(item, null, destination)

        assertTrue(outcome is ExportOutcome.Success)
        assertArrayEquals(entryBytes(2), destination.readBytes())
    }

    @Test
    fun `导出依赖归档管理器判定图片扩展名`() {
        val zip = createZip(listOf("a.jpg"))
        assertTrue(ZipArchiveManager.isImageFile("a.jpg"))
        val destination = File(tempFolder.newFolder("out_stateless"), "a.jpg")
        val item = PackImage("p", "a.jpg", "a.jpg", directFilePath = zip.absolutePath)
        MediaExportManager.exportToFile(item, null, destination)
        assertArrayEquals(entryBytes(0), destination.readBytes())
    }
}
