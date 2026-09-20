package com.watchpicture.app.archive

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
}
