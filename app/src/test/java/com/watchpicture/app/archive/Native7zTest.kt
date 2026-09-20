package com.watchpicture.app.archive

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Native7zTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var plain7z: File
    private val testBytes = byteArrayOf(1, 2, 3, 4, 5)

    @Before
    fun setUp() {
        plain7z = tempFolder.newFile("sample.7z")
        SevenZOutputFile(plain7z).use { out ->
            val entry = out.createArchiveEntry(tempFolder.newFile("tmp"), "photo.jpg")
            entry.size = testBytes.size.toLong()
            out.putArchiveEntry(entry)
            out.write(testBytes)
            out.closeArchiveEntry()
        }
    }

    @Test
    fun `isAvailable gracefully returns false on host JVM without crashing`() {
        // On desktop JVM host without Android .so loaded, must be safe boolean false
        assertFalse(Native7z.isAvailable)
    }

    @Test
    fun `open and openFd return null safely when native library is not available`() {
        assertNull(Native7zArchiveSession.open(plain7z.absolutePath))
        assertNull(Native7zArchiveSession.open(plain7z.absolutePath, "secret"))
        assertNull(Native7zArchiveSession.openFd(-1))
        assertNull(Native7zArchiveSession.openFd(-1, "secret"))
    }

    @Test
    fun `SevenZArchiveManager seamlessly falls back to Commons Compress when native is absent`() {
        val manager = SevenZArchiveManager()
        val entries = manager.getImageEntries(plain7z)
        assertEquals(1, entries.size)
        assertEquals("photo.jpg", entries[0].name)

        val stream = manager.getEntryInputStream(plain7z, "photo.jpg")
        val content = stream.use { it.readBytes() }
        assertTrue(content.contentEquals(testBytes))
    }

    @Test
    fun `extractSequentialEntries works reliably with fallback`() {
        val manager = SevenZArchiveManager()
        var extracted = false
        manager.extractSequentialEntries(plain7z, listOf("photo.jpg"), password = null) { name, stream ->
            if (name == "photo.jpg") {
                val content = stream.readBytes()
                assertTrue(content.contentEquals(testBytes))
                extracted = true
            }
        }
        assertTrue("Entry must be extracted via sequential stream", extracted)
    }
}
