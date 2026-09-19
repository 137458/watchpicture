package com.watchpicture.app.archive

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ZipArchiveManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var plainZip: File
    private lateinit var aesZip: File
    private val correctPassword = "StrongPassword@123"
    private val testImageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)

    @Before
    fun setUp() {
        // Create a plain ZIP archive
        plainZip = tempFolder.newFile("plain.zip")
        ZipFile(plainZip).use { zip ->
            val p1 = ZipParameters().apply { fileNameInZip = "10_page.jpg" }
            zip.addStream(ByteArrayInputStream(testImageBytes), p1)
            val p2 = ZipParameters().apply { fileNameInZip = "2_page.jpg" }
            zip.addStream(ByteArrayInputStream(testImageBytes), p2)
            val p3 = ZipParameters().apply { fileNameInZip = "notes.txt" }
            zip.addStream(ByteArrayInputStream("ignored".toByteArray()), p3)
            val p4 = ZipParameters().apply { fileNameInZip = "__MACOSX/._2_page.jpg" }
            zip.addStream(ByteArrayInputStream(byteArrayOf(0)), p4)
        }

        // Create an AES-256 encrypted ZIP archive
        aesZip = tempFolder.newFile("encrypted_aes.zip")
        ZipFile(aesZip, correctPassword.toCharArray()).use { zip ->
            val p = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                fileNameInZip = "secret_pic.png"
            }
            zip.addStream(ByteArrayInputStream(testImageBytes), p)
        }
    }

    @Test
    fun `detects encryption status accurately`() {
        val manager = ZipArchiveManager()
        assertFalse(manager.isEncrypted(plainZip))
        assertTrue(manager.isEncrypted(aesZip))
    }

    @Test
    fun `lists image entries with natural sorting and filters non-images and macOS artifacts`() {
        val manager = ZipArchiveManager()
        val entries = manager.getImageEntries(plainZip)

        assertEquals(2, entries.size)
        // 2_page.jpg must come before 10_page.jpg by natural sort
        assertEquals("2_page.jpg", entries[0].name)
        assertEquals("10_page.jpg", entries[1].name)
    }

    @Test
    fun `validates password for encrypted archive`() {
        val manager = ZipArchiveManager()

        // Correct password
        assertTrue(manager.verifyPassword(aesZip, correctPassword))

        // Wrong password
        assertFalse(manager.verifyPassword(aesZip, "WrongPass"))
    }

    @Test
    fun `streams entry content without writing to disk`() {
        val manager = ZipArchiveManager()
        val entries = manager.getImageEntries(aesZip, password = correctPassword)
        assertEquals(1, entries.size)

        val inputStream = manager.getEntryInputStream(aesZip, entries[0].name, password = correctPassword)
        val readBytes = inputStream.use { it.readBytes() }

        assertArrayEquals(testImageBytes, readBytes)
    }
}
