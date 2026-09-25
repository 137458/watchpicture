package com.watchpicture.app.archive

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class SevenZArchiveManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var plain7z: File
    private lateinit var headerEncrypted7z: File
    private val testImageBytes1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val testImageBytes2 = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 4, 5, 6)

    private val manager = SevenZArchiveManager()

    @Before
    fun setUp() {
        // 1. Create a plain 7z archive using SevenZOutputFile
        plain7z = tempFolder.newFile("plain.7z")
        SevenZOutputFile(plain7z).use { out ->
            // Add 10_page.jpg
            val entry1 = out.createArchiveEntry(tempFolder.newFile("temp1"), "10_page.jpg")
            entry1.size = testImageBytes1.size.toLong()
            out.putArchiveEntry(entry1)
            out.write(testImageBytes1)
            out.closeArchiveEntry()

            // Add 2_page.jpg
            val entry2 = out.createArchiveEntry(tempFolder.newFile("temp2"), "2_page.jpg")
            entry2.size = testImageBytes1.size.toLong()
            out.putArchiveEntry(entry2)
            out.write(testImageBytes1)
            out.closeArchiveEntry()

            // Add sub/3_page.png
            val entry3 = out.createArchiveEntry(tempFolder.newFile("temp3"), "sub/3_page.png")
            entry3.size = testImageBytes2.size.toLong()
            out.putArchiveEntry(entry3)
            out.write(testImageBytes2)
            out.closeArchiveEntry()

            // Add non-image file
            val entryTxt = out.createArchiveEntry(tempFolder.newFile("tempTxt"), "readme.txt")
            val txtBytes = "hello".toByteArray()
            entryTxt.size = txtBytes.size.toLong()
            out.putArchiveEntry(entryTxt)
            out.write(txtBytes)
            out.closeArchiveEntry()

            // Add macOS artifact
            val entryMac = out.createArchiveEntry(tempFolder.newFile("tempMac"), "__MACOSX/._2_page.jpg")
            entryMac.size = 1
            out.putArchiveEntry(entryMac)
            out.write(byteArrayOf(0))
            out.closeArchiveEntry()
        }

        // 2. Create Header Encrypted 7z fixture (EncryptedHeader)
        // Self-contained sparse fixture with 7z header pointing to AES256 EncodedHeader
        headerEncrypted7z = tempFolder.newFile("header_encrypted.7z")
        val headerBytes = java.util.Base64.getDecoder().decode("N3q8ryccAAQoRCJsIHQ0GAAAAABCAAAAAAAAAFJOLdA=")
        val tailBytes = java.util.Base64.getDecoder().decode(
            "w+nIlu7VSl6K33+LeSQdh57BzHjRJSkuZxHbFqC9aIigD+NhiP54QhSUYyn6u/sDLj5605uG2dpYo/QjV909lpVnKhLWSxVD" +
            "SksXwmuVkJ6aR44ntCn25nel+o+eIabhqscNf70ZiKab2JHGZpqycDKf49qHZI+BbKhSTRwcJIJCKxZ7Bn0glZWNlQw7pjvg" +
            "1NVvVCoi7HkxzLZQ93jMp7Nmb8REEdXv6eivVwh9w7lAPlNDJ0t3MTcBiTHdZfMjf2av/SGsKQVqwCqB86/l1aDMYwAVwepa" +
            "kqNV/r0c+F0FfdE6kTJYp7c5YA9Whs5N0Xmwmt1UNx5HTHl0kAUFU4DUWvuJXeO1lnYzfVcKcceAsjHpjQ1XlXSROVAmhV+6" +
            "c8yOk+ZAXrX1rDo8FVUjfyf6HcJVjoHhjpy+wWu7IzVhoxjxajO7P6MKK5Am2b3WtUdVQ7f3MODcrxDbLTRp71eZACuPprqD" +
            "sQDMnc8965QJj3Tmg+9D1m11LF+wtMguBV3cYKe8kAuUjT5aDq5eZZauIT6HMYiv4lZKn6fqax8hQMYEVYdDh9+S5MXy3jqq" +
            "RTxN0W6Oqca4xXJ7Do+U6UbX0x+4U9tEm3GfiEBPC3mlooruDTMIMrVCvw9MiJNHBUobSgTYryPf33uztqveT3QRpDxVaL9+" +
            "qVZt3aqx228bRV7SwLW54MtWK9/ZX4lZMh4eRXCXplGWw7SgbxdstZ1yRmnYpKBtBLYxYxpxd7Unf6xtvsGU3+9HxGHAo8d2" +
            "g10wjJ6ED+VihLKLORdz2WJf9rpvBM7t0SsC6a7cJh4h9dUAIwDzwiKff39die6M3VZfHdl0/VrBlO/5Hu8TexzTaQ7AN7I/" +
            "LTQHP1qWZKX04xLv5P1YyI55MJ0HMJf/FwbwoHE0GAEJgoAABwsBAAIkBvEHARJTD6BNk80BCEFGm8WqMrP0xJ4jAwEBBV0A" +
            "EAAAAQAMgn6NsgoB9A3hSwAA"
        )

        java.io.RandomAccessFile(headerEncrypted7z, "rw").use { raf ->
            raf.write(headerBytes)
            raf.seek(406090144L)
            raf.write(tailBytes)
        }
    }

    @Test
    fun `detects 7z magic signature accurately`() {
        assertTrue(manager.isValidSevenZArchive(plain7z))
        if (headerEncrypted7z.exists() && headerEncrypted7z.length() > 0) {
            assertTrue(manager.isValidSevenZArchive(headerEncrypted7z))
        }

        val non7z = tempFolder.newFile("dummy.txt").apply { writeText("not a 7z") }
        assertFalse(manager.isValidSevenZArchive(non7z))

        val dummyEmpty = tempFolder.newFile("empty.7z")
        assertFalse(manager.isValidSevenZArchive(dummyEmpty))
    }

    @Test
    fun `extracts plain 7z image entries in natural order and ignores non-images`() {
        val entries = manager.getMediaEntries(plain7z)
        assertEquals(3, entries.size)
        assertEquals("2_page.jpg", entries[0].name)
        assertEquals("10_page.jpg", entries[1].name)
        assertEquals("sub/3_page.png", entries[2].name)
        assertFalse(entries[0].isEncrypted)
    }

    @Test
    fun `streams entry content directly from 7z archive`() {
        val stream = manager.getEntryInputStream(plain7z, "sub/3_page.png")
        val bytes = stream.use { it.readBytes() }
        assertArrayEquals(testImageBytes2, bytes)
    }

    @Test
    fun `detects header encrypted 7z and returns empty entries when no password is provided`() {
        if (!headerEncrypted7z.exists() || headerEncrypted7z.length() == 0L) return
        assertTrue(manager.isEncrypted(headerEncrypted7z))

        val entries = manager.getMediaEntries(headerEncrypted7z, null)
        assertTrue(entries.isEmpty())

        // Wrong password fails verification
        assertFalse(manager.verifyPassword(headerEncrypted7z, "wrong_password_123"))
    }

    @Test
    fun `extracts sequential entries in one pass to avoid quadratic solid decompression`() {
        val extracted = mutableMapOf<String, ByteArray>()
        manager.extractSequentialEntries(plain7z, listOf("2_page.jpg", "10_page.jpg")) { name, stream ->
            extracted[name] = stream.readBytes()
        }
        assertEquals(2, extracted.size)
        assertArrayEquals(testImageBytes1, extracted["2_page.jpg"])
        assertArrayEquals(testImageBytes1, extracted["10_page.jpg"])
    }

    @Test
    fun `getMediaEntries prewarms sessionManager even when password is null to avoid redundant archive opens`() {
        val freshManager = SevenZArchiveManager()
        assertEquals(0, freshManager.sessionManager.activeSessionCount)

        val entries = freshManager.getMediaEntries(plain7z, password = null)
        assertEquals(3, entries.size)
        assertEquals(
            "getMediaEntries should keep session warm in sessionManager for subsequent thumbnail/image loads",
            1,
            freshManager.sessionManager.activeSessionCount
        )
    }
}
