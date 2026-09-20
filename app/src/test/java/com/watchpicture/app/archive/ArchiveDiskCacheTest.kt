package com.watchpicture.app.archive

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ArchiveDiskCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var dummyZip: File

    @Before
    fun setUp() {
        cacheDir = tempFolder.newFolder("test_archive_cache")
        dummyZip = tempFolder.newFile("sample.zip")
    }

    @Test
    fun `streams entry to disk and hits cache on second call`() {
        val cache = ArchiveDiskCache(cacheDir, maxSizeBytes = 10 * 1024 * 1024)
        val entryName = "images/photo_01.jpg"
        val payload = "TestDataPayloadForImage_1234567890".toByteArray()
        var streamOpenCount = 0

        val file1 = cache.getOrPut(dummyZip, entryName, password = null) {
            streamOpenCount++
            ByteArrayInputStream(payload)
        }

        assertTrue("Cached file must exist on disk", file1.exists())
        assertEquals(payload.size.toLong(), file1.length())
        assertArrayEquals(payload, file1.readBytes())
        assertEquals("Stream opened exactly once for initial cache put", 1, streamOpenCount)

        // Second retrieval must hit disk cache without reopening the source stream
        val file2 = cache.getOrPut(dummyZip, entryName, password = null) {
            streamOpenCount++
            fail("Producer should not be invoked on cache hit")
            ByteArrayInputStream(payload)
        }

        assertEquals("Same cached file must be returned", file1.absolutePath, file2.absolutePath)
        assertEquals("Stream must NOT be reopened on cache hit", 1, streamOpenCount)

        // get() should return the cached file directly
        val fileDirect = cache.get(dummyZip, entryName, password = null)
        assertNotNull(fileDirect)
        assertEquals(file1.absolutePath, fileDirect?.absolutePath)

        // get() should return null for non-cached entry
        val fileNonExistent = cache.get(dummyZip, "non_existent.jpg", password = null)
        assertNull(fileNonExistent)
    }

    @Test
    fun `differentiates cache key by password`() {
        val cache = ArchiveDiskCache(cacheDir, maxSizeBytes = 10 * 1024 * 1024)
        val entryName = "secret.jpg"
        val payload1 = "ContentPass1".toByteArray()
        val payload2 = "ContentPass2".toByteArray()

        val file1 = cache.getOrPut(dummyZip, entryName, password = "pwd1") {
            ByteArrayInputStream(payload1)
        }
        val file2 = cache.getOrPut(dummyZip, entryName, password = "pwd2") {
            ByteArrayInputStream(payload2)
        }

        assertNotEquals("Files with different passwords must have different cache keys", file1.name, file2.name)
        assertArrayEquals(payload1, file1.readBytes())
        assertArrayEquals(payload2, file2.readBytes())
    }

    @Test
    fun `enforces LRU eviction when size exceeds limit`() {
        // Small limit: 150 bytes
        val cache = ArchiveDiskCache(cacheDir, maxSizeBytes = 150)
        val block1 = ByteArray(60) { 1 }
        val block2 = ByteArray(60) { 2 }
        val block3 = ByteArray(60) { 3 }

        val f1 = cache.getOrPut(dummyZip, "01.jpg", null) { ByteArrayInputStream(block1) }
        Thread.sleep(20)
        val f2 = cache.getOrPut(dummyZip, "02.jpg", null) { ByteArrayInputStream(block2) }
        Thread.sleep(20)
        // 60 + 60 = 120 <= 150, both exist
        assertTrue(f1.exists())
        assertTrue(f2.exists())

        // Adding block3 brings total to 180 > 150 -> oldest f1 must be evicted
        val f3 = cache.getOrPut(dummyZip, "03.jpg", null) { ByteArrayInputStream(block3) }
        assertTrue("Newly added file must exist", f3.exists())
        assertTrue("Second file should remain", f2.exists())
        assertFalse("Oldest file f1 must have been evicted", f1.exists())
    }

    @Test
    fun `clears encrypted entries on security lock`() {
        val cache = ArchiveDiskCache(cacheDir, maxSizeBytes = 10 * 1024 * 1024)
        val plainFile = cache.getOrPut(dummyZip, "plain.jpg", password = null) {
            ByteArrayInputStream("plain".toByteArray())
        }
        val encryptedFile = cache.getOrPut(dummyZip, "enc.jpg", password = "secret") {
            ByteArrayInputStream("encrypted".toByteArray())
        }

        assertTrue(plainFile.exists())
        assertTrue(encryptedFile.exists())

        cache.clearEncrypted()

        assertTrue("Plain file must still exist after clearing encrypted cache", plainFile.exists())
        assertFalse("Encrypted file must be wiped", encryptedFile.exists())
    }

    @Test
    fun `throws IOException when stream produces zero bytes without creating corrupted file`() {
        val cache = ArchiveDiskCache(cacheDir, maxSizeBytes = 10 * 1024 * 1024)
        try {
            cache.getOrPut(dummyZip, "empty.jpg", password = null) {
                ByteArrayInputStream(ByteArray(0))
            }
            fail("Must throw IOException on empty stream")
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("0 bytes") == true || e.message?.contains("empty") == true)
        }

        // Cache dir must not contain empty.jpg or zero-byte corrupted files
        val files = cacheDir.listFiles() ?: emptyArray()
        assertEquals("Cache dir must remain empty of corrupted files", 0, files.size)
    }

    @Test
    fun `tracks size and supports striped concurrent access without lock leaks`() {
        val cache = ArchiveDiskCache(cacheDir, maxSizeBytes = 500 * 1024)
        val threadCount = 8
        val itemsPerThread = 20
        val payload = ByteArray(1024) { 42 } // 1KB each

        val threads = (0 until threadCount).map { tIdx ->
            Thread {
                for (i in 0 until itemsPerThread) {
                    val entryName = "entry_${tIdx}_$i.jpg"
                    cache.getOrPut(dummyZip, entryName, password = null) {
                        ByteArrayInputStream(payload)
                    }
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // All entries processed without deadlock or lock crash
        val files = cacheDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue("Files should be cached", files.isNotEmpty())
        val totalSize = files.sumOf { it.length() }
        assertTrue("Total size must stay within limit of 500KB", totalSize <= 500 * 1024)
    }
}
