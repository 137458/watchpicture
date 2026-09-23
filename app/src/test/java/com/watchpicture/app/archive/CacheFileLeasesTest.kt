package com.watchpicture.app.archive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Covers [CacheFileLeases] and the lease-aware eviction in [ArchiveDiskCache] / [ThumbnailDiskCache]:
 * a file handed to an in-flight reader must never be deleted by LRU trimming.
 */
class CacheFileLeasesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        CacheFileLeases.clear()
    }

    @Test
    fun `acquire and release toggles leased state`() {
        val path = File(tempFolder.root, "a.bin").absolutePath
        assertFalse(CacheFileLeases.isLeased(path))
        val lease = CacheFileLeases.acquire(path)
        assertTrue(CacheFileLeases.isLeased(path))
        lease.close()
        assertFalse(CacheFileLeases.isLeased(path))
    }

    @Test
    fun `close is idempotent and does not over-release`() {
        val path = File(tempFolder.root, "b.bin").absolutePath
        val lease1 = CacheFileLeases.acquire(path)
        val lease2 = CacheFileLeases.acquire(path)
        assertTrue(CacheFileLeases.isLeased(path))

        lease1.close()
        assertTrue("Still leased by lease2", CacheFileLeases.isLeased(path))
        // A second close must NOT drop lease2's reference (Coil may close twice).
        lease1.close()
        assertTrue("Double close must not over-decrement", CacheFileLeases.isLeased(path))

        lease2.close()
        assertFalse(CacheFileLeases.isLeased(path))
    }

    @Test
    fun `concurrent acquire and release never loses a lease`() {
        val path = File(tempFolder.root, "c.bin").absolutePath
        val threads = 8
        val iterations = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val futures = (0 until threads).map {
            pool.submit {
                start.await()
                repeat(iterations) {
                    CacheFileLeases.acquire(path).close()
                }
            }
        }
        start.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertFalse("All leases released -> not leased", CacheFileLeases.isLeased(path))
    }

    @Test
    fun `archive disk cache trim keeps leased files`() {
        val cacheDir = tempFolder.newFolder("archive_lease")
        val dummyZip = tempFolder.newFile("lease.zip")
        val cache = ArchiveDiskCache(cacheDir, maxSizeBytes = 70)

        val block = ByteArray(30) { 1 }
        val now = System.currentTimeMillis()
        val f1 = cache.getOrPut(dummyZip, "1.jpg", null) { ByteArrayInputStream(block) }
        f1.setLastModified(now - 3000)
        val f2 = cache.getOrPut(dummyZip, "2.jpg", null) { ByteArrayInputStream(block) }
        f2.setLastModified(now - 1500)

        // Lease the oldest file; the trim triggered by the third write must skip it.
        val lease = CacheFileLeases.acquire(f1.absolutePath)
        val f3 = cache.getOrPut(dummyZip, "3.jpg", null) { ByteArrayInputStream(block) }
        f3.setLastModified(now)
        assertTrue("Leased (oldest) file must survive eviction", f1.exists())
        assertTrue("Newly written file must exist", f3.exists())
        assertFalse("Oldest unleased file must be evicted", f2.exists())

        // After release the file becomes evictable again.
        lease.close()
        assertFalse(CacheFileLeases.isLeased(f1.absolutePath))
        cache.getOrPut(dummyZip, "4.jpg", null) { ByteArrayInputStream(block) }
        assertFalse("Released file is evictable again", f1.exists())
    }

    @Test
    fun `thumbnail disk cache trim keeps leased files`() {
        val cacheDir = tempFolder.newFolder("thumb_lease")
        val dummyZip = tempFolder.newFile("thumb_lease.zip")
        val cache = ThumbnailDiskCache(cacheDir, maxSizeBytes = 70)

        val data = ByteArray(30) { it.toByte() }
        val now = System.currentTimeMillis()
        val f1 = cache.getOrPut(dummyZip, "1.jpg", 360, null) { data.inputStream() }
        f1.setLastModified(now - 3000)
        val f2 = cache.getOrPut(dummyZip, "2.jpg", 360, null) { data.inputStream() }
        f2.setLastModified(now - 1500)

        val lease = CacheFileLeases.acquire(f1.absolutePath)
        val f3 = cache.getOrPut(dummyZip, "3.jpg", 360, null) { data.inputStream() }
        f3.setLastModified(now)
        assertTrue("Leased thumbnail must survive trim", f1.exists())
        assertTrue(f3.exists())
        assertFalse("Oldest unleased thumbnail must be evicted", f2.exists())

        lease.close()
        cache.getOrPut(dummyZip, "4.jpg", 360, null) { data.inputStream() }
        assertFalse("Released thumbnail is evictable", f1.exists())
    }
}
