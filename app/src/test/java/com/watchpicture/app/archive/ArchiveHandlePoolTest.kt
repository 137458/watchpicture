package com.watchpicture.app.archive

import kotlinx.coroutines.async
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ArchiveHandlePoolTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var zipFile1: File
    private lateinit var zipFile2: File
    private lateinit var zipFile3: File
    private val testBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    @Before
    fun setUp() {
        zipFile1 = tempFolder.newFile("test1.zip")
        ZipFile(zipFile1).use {
            it.addStream(ByteArrayInputStream(testBytes), ZipParameters().apply { fileNameInZip = "img1.png" })
        }
        zipFile2 = tempFolder.newFile("test2.zip")
        ZipFile(zipFile2).use {
            it.addStream(ByteArrayInputStream(testBytes), ZipParameters().apply { fileNameInZip = "img2.png" })
        }
        zipFile3 = tempFolder.newFile("test3.zip")
        ZipFile(zipFile3).use {
            it.addStream(ByteArrayInputStream(testBytes), ZipParameters().apply { fileNameInZip = "img3.png" })
        }
    }

    @Test
    fun `reuses native zip handle across multiple reads`() {
        val pool = ArchiveHandlePool(maxPoolSize = 2)

        // First read opens handle
        val stream1 = pool.openNativeEntryStream(zipFile1, "img1.png")
        assertNotNull(stream1)
        assertArrayEquals(testBytes, stream1!!.use { it.readBytes() })
        assertEquals(1, pool.activeNativeHandleCount)

        // Second read reuses same handle
        val stream2 = pool.openNativeEntryStream(zipFile1, "img1.png")
        assertNotNull(stream2)
        assertArrayEquals(testBytes, stream2!!.use { it.readBytes() })
        assertEquals(1, pool.activeNativeHandleCount)

        pool.closeAll()
        assertEquals(0, pool.activeNativeHandleCount)
    }

    @Test
    fun `evicts least recently used handle when capacity exceeded`() {
        val pool = ArchiveHandlePool(maxPoolSize = 2)

        // Open file 1 and file 2
        pool.openNativeEntryStream(zipFile1, "img1.png")?.close()
        pool.openNativeEntryStream(zipFile2, "img2.png")?.close()
        assertEquals(2, pool.activeNativeHandleCount)

        // Open file 3 -> should evict file 1 (LRU)
        pool.openNativeEntryStream(zipFile3, "img3.png")?.close()
        assertEquals(2, pool.activeNativeHandleCount)

        pool.closeAll()
    }

    @Test
    fun `closes handle when file is explicitly closed or deleted`() {
        val pool = ArchiveHandlePool(maxPoolSize = 2)
        pool.openNativeEntryStream(zipFile1, "img1.png")?.close()
        assertEquals(1, pool.activeNativeHandleCount)

        pool.close(zipFile1)
        assertEquals(0, pool.activeNativeHandleCount)
    }

    @Test
    fun `concurrent reads on encrypted zip using handle pool do not corrupt or crash`() = kotlinx.coroutines.runBlocking {
        val encZip = tempFolder.newFile("enc_test.zip")
        val password = "SecretPassword123"
        val bytes1 = ByteArray(100_000) { (it % 251).toByte() }
        val bytes2 = ByteArray(100_000) { ((it + 37) % 251).toByte() }
        val bytes3 = ByteArray(100_000) { ((it + 89) % 251).toByte() }

        ZipFile(encZip, password.toCharArray()).use { zip ->
            val p1 = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
                fileNameInZip = "file1.bin"
            }
            zip.addStream(ByteArrayInputStream(bytes1), p1)

            val p2 = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
                fileNameInZip = "file2.bin"
            }
            zip.addStream(ByteArrayInputStream(bytes2), p2)

            val p3 = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
                fileNameInZip = "file3.bin"
            }
            zip.addStream(ByteArrayInputStream(bytes3), p3)
        }

        val pool = ArchiveHandlePool(maxPoolSize = 2)

        val tasks = (0 until 9).map { idx ->
            async(kotlinx.coroutines.Dispatchers.IO) {
                val (fileName, expected) = when (idx % 3) {
                    0 -> "file1.bin" to bytes1
                    1 -> "file2.bin" to bytes2
                    else -> "file3.bin" to bytes3
                }
                val stream = pool.openEncryptedEntryStream(encZip, fileName, password)
                assertNotNull("Stream must not be null for $fileName", stream)
                val readData = stream!!.use { it.readBytes() }
                assertArrayEquals("Data mismatch in concurrent read of $fileName", expected, readData)
            }
        }

        tasks.forEach { it.await() }
        assertEquals(1, pool.activeEncryptedHandleCount)
        pool.closeAll()
        assertEquals(0, pool.activeEncryptedHandleCount)
    }

    @Test
    fun `supports directory stream reading in openNativeEntryStream and openEncryptedEntryStream`() {
        val dir = tempFolder.newFolder("local_folder")
        val sampleFile = File(dir, "photo.jpg")
        val sampleData = byteArrayOf(10, 20, 30, 40)
        sampleFile.writeBytes(sampleData)

        val pool = ArchiveHandlePool(maxPoolSize = 2)

        val nativeStream = pool.openNativeEntryStream(dir, "photo.jpg")
        assertNotNull(nativeStream)
        assertArrayEquals(sampleData, nativeStream!!.use { it.readBytes() })

        val encStream = pool.openEncryptedEntryStream(dir, "photo.jpg", "dummyPassword")
        assertNotNull(encStream)
        assertArrayEquals(sampleData, encStream!!.use { it.readBytes() })
    }
}
