package com.watchpicture.app.archive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class ThumbnailDiskCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `stores and retrieves downsampled thumbnail without raw dump`() {
        val cacheDir = tempFolder.newFolder("thumb_cache")
        val cache = ThumbnailDiskCache(cacheDir, maxSizeBytes = 10 * 1024 * 1024)

        val dummyZip = tempFolder.newFile("dummy.zip")
        val entryName = "cover.jpg"

        // Put a fake thumbnail byte stream
        val dummyData = byteArrayOf(1, 2, 3, 4, 5, 6)
        val thumbFile = cache.getOrPut(dummyZip, entryName, 360, password = null) {
            dummyData.inputStream()
        }

        assertNotNull(thumbFile)
        assertTrue(thumbFile.exists())
        assertEquals(dummyData.size.toLong(), thumbFile.length())

        // Re-read should hit cache
        val hit = cache.get(dummyZip, entryName, 360, password = null)
        assertNotNull(hit)
        assertEquals(thumbFile.absolutePath, hit?.absolutePath)
    }

    @Test
    fun `trims to max size on limit breach`() {
        val cacheDir = tempFolder.newFolder("thumb_cache_trim")
        val cache = ThumbnailDiskCache(cacheDir, maxSizeBytes = 50)

        val dummyZip = tempFolder.newFile("dummy.zip")
        val data = ByteArray(30) { it.toByte() }

        cache.getOrPut(dummyZip, "1.jpg", 360, null) { data.inputStream() }
        Thread.sleep(10)
        cache.getOrPut(dummyZip, "2.jpg", 360, null) { data.inputStream() }

        // Total 60 > 50 -> must have trimmed oldest
        val remaining = cacheDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: emptyList()
        val totalLen = remaining.sumOf { it.length() }
        assertTrue("Total size ($totalLen) should be <= 50", totalLen <= 50)
    }

    @Test
    fun `streams large input without in-memory buffering failure`() {
        val cacheDir = tempFolder.newFolder("thumb_cache_stream")
        val cache = ThumbnailDiskCache(cacheDir, maxSizeBytes = 10 * 1024 * 1024)

        val dummyZip = tempFolder.newFile("dummy_large.zip")
        val entryName = "large_image.jpg"

        // 256 KB non-seekable streaming data
        val largeData = ByteArray(256 * 1024) { (it % 255).toByte() }
        val thumbFile = cache.getOrPut(dummyZip, entryName, 360, password = null) {
            largeData.inputStream()
        }

        assertNotNull(thumbFile)
        assertTrue(thumbFile.exists())
        assertEquals(largeData.size.toLong(), thumbFile.length())
    }

    @Test
    fun `getOrPutResult returns ThumbnailResult with valid file`() {
        val cacheDir = tempFolder.newFolder("thumb_cache_result")
        val cache = ThumbnailDiskCache(cacheDir, maxSizeBytes = 10 * 1024 * 1024)

        val dummyZip = tempFolder.newFile("dummy_result.zip")
        val entryName = "result_test.jpg"
        val sampleData = byteArrayOf(10, 20, 30, 40)

        val result1 = cache.getOrPutResult(
            zipFile = dummyZip,
            entryName = entryName,
            targetSizePx = 360,
            password = null,
            keepBitmapInMemory = true
        ) {
            sampleData.inputStream()
        }

        assertNotNull(result1.file)
        assertTrue(result1.file.exists())
        assertEquals(sampleData.size.toLong(), result1.file.length())

        // Second call hits fast path
        val result2 = cache.getOrPutResult(
            zipFile = dummyZip,
            entryName = entryName,
            targetSizePx = 360,
            password = null,
            keepBitmapInMemory = true
        ) {
            throw IllegalStateException("Must hit cache, not open stream")
        }

        assertEquals(result1.file.absolutePath, result2.file.absolutePath)
    }

    @Test
    fun `handles 512KB non-seekable streaming payload without truncation or corruption`() {
        val cacheDir = tempFolder.newFolder("thumb_large_512")
        val cache = ThumbnailDiskCache(cacheDir, maxSizeBytes = 10 * 1024 * 1024)

        val dummyZip = tempFolder.newFile("dummy_512.zip")
        val entryName = "dslr_raw_512.jpg"
        val payload = ByteArray(512 * 1024) { (it % 251).toByte() }

        val res = cache.getOrPutResult(dummyZip, entryName, 360, password = null, keepBitmapInMemory = false) {
            payload.inputStream()
        }

        assertNotNull(res.file)
        assertTrue(res.file.exists())
        assertEquals(payload.size.toLong(), res.file.length())
        assertArrayEquals(payload, res.file.readBytes())
    }

    @Test
    fun `elastic fallback returns existing size when requested size is missing`() {
        val cacheDir = tempFolder.newFolder("thumb_fallback")
        val cache = ThumbnailDiskCache(cacheDir, maxSizeBytes = 10 * 1024 * 1024)

        val dummyZip = tempFolder.newFile("dummy_fallback.zip")
        val entryName = "page_01.jpg"
        val sampleData = byteArrayOf(11, 22, 33, 44)

        // Store size 360
        val file360 = cache.getOrPut(dummyZip, entryName, 360, password = null) {
            sampleData.inputStream()
        }
        assertNotNull(file360)
        assertTrue(file360.exists())

        // Request size 720 which has not been extracted: must return the 360 thumbnail as elastic fallback
        val fallbackHit = cache.get(dummyZip, entryName, 720, password = null)
        assertNotNull("Requesting 720 should return existing 360 thumbnail as elastic fallback", fallbackHit)
        assertEquals(file360.absolutePath, fallbackHit?.absolutePath)

        // Requesting an uncached entry must return null
        val miss = cache.get(dummyZip, "uncached_page.jpg", 720, password = null)
        org.junit.Assert.assertNull("Uncached entry should return null", miss)
    }

    @Test
    fun `getOrPutResultFromByteBuffer stores and retrieves DirectByteBuffer payload without heap array`() {
        val cacheDir = tempFolder.newFolder("thumb_direct_buf")
        val cache = ThumbnailDiskCache(cacheDir, maxSizeBytes = 10 * 1024 * 1024)

        val dummyZip = tempFolder.newFile("dummy_direct.zip")
        val entryName = "native_entry.jpg"

        val rawBytes = byteArrayOf(5, 4, 3, 2, 1)
        val directBuffer = ByteBuffer.allocateDirect(rawBytes.size)
        directBuffer.put(rawBytes)
        directBuffer.flip()

        val res = cache.getOrPutResultFromByteBuffer(
            zipFile = dummyZip,
            entryName = entryName,
            targetSizePx = 360,
            password = null,
            keepBitmapInMemory = false,
            buffer = directBuffer
        )

        assertNotNull(res.file)
        assertTrue(res.file.exists())
        assertEquals(rawBytes.size.toLong(), res.file.length())
        assertArrayEquals(rawBytes, res.file.readBytes())

        // Fast path hit
        val hit = cache.get(dummyZip, entryName, 360, password = null)
        assertNotNull(hit)
        assertEquals(res.file.absolutePath, hit?.absolutePath)
    }
}
