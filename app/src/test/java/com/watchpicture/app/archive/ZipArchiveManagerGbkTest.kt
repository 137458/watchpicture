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
import java.nio.charset.Charset

class ZipArchiveManagerGbkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var gbkZip: File
    private val password = "GbkPassword@123"
    private val testBytes = byteArrayOf(1, 2, 3, 4, 5)
    private val gbkCharset = Charset.forName("GBK")

    @Before
    fun setUp() {
        gbkZip = tempFolder.newFile("gbk_archive.zip")
        ZipFile(gbkZip, password.toCharArray()).use { zip ->
            zip.charset = gbkCharset
            val p1 = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                fileNameInZip = "第01话/封面.jpg"
            }
            zip.addStream(ByteArrayInputStream(testBytes), p1)

            val p2 = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                fileNameInZip = "第01话/002_插画.png"
            }
            zip.addStream(ByteArrayInputStream(testBytes), p2)
        }
    }

    @Test
    fun `supports reading and decrypting GBK encoded archive entries`() {
        val manager = ZipArchiveManager()

        assertTrue("Must detect encryption for GBK archive", manager.isEncrypted(gbkZip))
        assertTrue("Must verify correct password on GBK archive", manager.verifyPassword(gbkZip, password))
        assertFalse("Must fail on wrong password", manager.verifyPassword(gbkZip, "BadPassword"))

        val entries = manager.getMediaEntries(gbkZip, password)
        assertEquals("Must discover both image entries", 2, entries.size)

        val stream = manager.getEntryInputStream(gbkZip, entries[0].name, password)
        val readBytes = stream.use { it.readBytes() }
        assertArrayEquals("Decrypted bytes must match", testBytes, readBytes)
    }
}
