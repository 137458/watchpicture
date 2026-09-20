package com.watchpicture.app.archive

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SevenZSessionManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var archiveDiskCache: ArchiveDiskCache
    private lateinit var solid7z: File
    private lateinit var sessionManager: SevenZSessionManager

    private val payload0 = "Image00ContentPayload".toByteArray()
    private val payload1 = "Image01ContentPayload".toByteArray()
    private val payload2 = "Image02ContentPayload".toByteArray()
    private val payload3 = "Image03ContentPayload".toByteArray()

    @Before
    fun setUp() {
        cacheDir = tempFolder.newFolder("session_test_cache")
        archiveDiskCache = ArchiveDiskCache(cacheDir)
        sessionManager = SevenZSessionManager(idleTimeoutMs = 1000L)

        solid7z = tempFolder.newFile("test_solid.7z")
        SevenZOutputFile(solid7z).use { out ->
            val e0 = out.createArchiveEntry(tempFolder.newFile("t0"), "00.jpg").apply { size = payload0.size.toLong() }
            out.putArchiveEntry(e0)
            out.write(payload0)
            out.closeArchiveEntry()

            val e1 = out.createArchiveEntry(tempFolder.newFile("t1"), "01.jpg").apply { size = payload1.size.toLong() }
            out.putArchiveEntry(e1)
            out.write(payload1)
            out.closeArchiveEntry()

            val e2 = out.createArchiveEntry(tempFolder.newFile("t2"), "02.jpg").apply { size = payload2.size.toLong() }
            out.putArchiveEntry(e2)
            out.write(payload2)
            out.closeArchiveEntry()

            val e3 = out.createArchiveEntry(tempFolder.newFile("t3"), "03.jpg").apply { size = payload3.size.toLong() }
            out.putArchiveEntry(e3)
            out.write(payload3)
            out.closeArchiveEntry()
        }
    }

    @Test
    fun `extractSequential extracts targets and maintains session without re-parsing from zero`() = runBlocking {
        // First request: page 00.jpg with lookahead 1
        var extracted0Bytes: ByteArray? = null
        val ok0 = sessionManager.extractSequential(solid7z, "00.jpg", password = null, lookahead = 1) { name, stream ->
            archiveDiskCache.getOrPut(solid7z, name, password = null) { stream }
            if (name == "00.jpg") extracted0Bytes = stream.readBytes()
        }

        assertTrue("First extraction must succeed", ok0)
        assertEquals(1, sessionManager.activeSessionCount)
        assertNotNull(archiveDiskCache.get(solid7z, "00.jpg", null))
        assertNotNull("Lookahead 1 should also have cached 01.jpg", archiveDiskCache.get(solid7z, "01.jpg", null))

        // Second request: page 02.jpg - advances session forward
        val ok2 = sessionManager.extractSequential(solid7z, "02.jpg", password = null, lookahead = 0) { name, stream ->
            archiveDiskCache.getOrPut(solid7z, name, password = null) { stream }
        }

        assertTrue("Second extraction forward must succeed", ok2)
        assertNotNull("02.jpg must be cached", archiveDiskCache.get(solid7z, "02.jpg", null))
        assertEquals("Session should remain open", 1, sessionManager.activeSessionCount)
    }

    @Test
    fun `backward request safely rewinds session and recovers`() = runBlocking {
        // Advance to 03.jpg first
        sessionManager.extractSequential(solid7z, "03.jpg", password = null, lookahead = 0) { name, stream ->
            archiveDiskCache.getOrPut(solid7z, name, password = null) { stream }
        }
        assertNotNull(archiveDiskCache.get(solid7z, "03.jpg", null))

        // Clear cache for 00.jpg to simulate uncached backward seek
        val cached0 = archiveDiskCache.get(solid7z, "00.jpg", null)
        cached0?.delete()
        assertNull(archiveDiskCache.get(solid7z, "00.jpg", null))

        // Now request 00.jpg (backward from 03.jpg)
        val okBackward = sessionManager.extractSequential(solid7z, "00.jpg", password = null, lookahead = 0) { name, stream ->
            archiveDiskCache.getOrPut(solid7z, name, password = null) { stream }
        }

        assertTrue("Backward seek must rewind and extract successfully", okBackward)
        assertNotNull("00.jpg must be cached after backward recovery", archiveDiskCache.get(solid7z, "00.jpg", null))
    }

    @Test
    fun `closeSession immediately frees session`() = runBlocking {
        sessionManager.extractSequential(solid7z, "00.jpg", password = null, lookahead = 0) { name, stream ->
            archiveDiskCache.getOrPut(solid7z, name, password = null) { stream }
        }
        assertEquals(1, sessionManager.activeSessionCount)

        sessionManager.closeSession(solid7z)
        assertEquals(0, sessionManager.activeSessionCount)
    }

    @Test
    fun `reapIdleSessions closes expired sessions`() = runBlocking {
        val shortTimeoutManager = SevenZSessionManager(idleTimeoutMs = 50L)
        shortTimeoutManager.extractSequential(solid7z, "00.jpg", password = null, lookahead = 0) { name, stream ->
            archiveDiskCache.getOrPut(solid7z, name, password = null) { stream }
        }
        assertEquals(1, shortTimeoutManager.activeSessionCount)

        Thread.sleep(80L)
        shortTimeoutManager.reapIdleSessions()
        assertEquals("Session should be reaped after idle timeout", 0, shortTimeoutManager.activeSessionCount)
    }

    @Test
    fun `prewarmSession and getEntries keep archive warm in memory`() {
        val warmed = sessionManager.prewarmSession(solid7z, password = null)
        assertTrue("prewarmSession must succeed", warmed)
        assertEquals(1, sessionManager.activeSessionCount)

        val entries = sessionManager.getEntries(solid7z, password = null)
        assertNotNull("getEntries must return pre-parsed list", entries)
        assertEquals(4, entries?.size)
        assertEquals("00.jpg", entries?.get(0)?.name)
        assertEquals("01.jpg", entries?.get(1)?.name)
        assertEquals("02.jpg", entries?.get(2)?.name)
        assertEquals("03.jpg", entries?.get(3)?.name)
    }

    @Test
    fun `extractThumbnail generates thumbnail and skips intermediate entries in memory`() = runBlocking {
        val thumbDir = tempFolder.newFolder("session_thumb_dir")
        val thumbCache = ThumbnailDiskCache(thumbDir)

        // Request thumbnail for 02.jpg directly
        val thumb = sessionManager.extractThumbnail(
            file = solid7z,
            targetEntryName = "02.jpg",
            targetSizePx = 100,
            password = null,
            thumbnailDiskCache = thumbCache,
            lookahead = 1
        )

        assertNotNull("Thumbnail file should be returned", thumb)
        assertTrue(thumb?.exists() == true && thumb.length() > 0L)

        // Verify lookahead 1 cached 03.jpg
        val lookaheadThumb = thumbCache.get(solid7z, "03.jpg", 100, password = null)
        assertNotNull("Lookahead thumbnail for 03.jpg should be cached", lookaheadThumb)

        // Intermediate 00.jpg and 01.jpg should be opportunistically cached in thumbnail cache to eliminate O(N^2) backward rewinds
        assertNotNull("Opportunistic thumbnail for 00.jpg should be cached", thumbCache.get(solid7z, "00.jpg", 100, password = null))
        assertNotNull("Opportunistic thumbnail for 01.jpg should be cached", thumbCache.get(solid7z, "01.jpg", 100, password = null))

        // Invariant: Intermediate 00.jpg and 01.jpg must NOT have been saved as full-res files to archiveDiskCache
        assertNull(archiveDiskCache.get(solid7z, "00.jpg", password = null))
        assertNull(archiveDiskCache.get(solid7z, "01.jpg", password = null))
    }

    @Test
    fun `extractSequential with allowRewind=false skips backward target without rewinding`() = runBlocking {
        // Advance cursor to 02.jpg
        val ok2 = sessionManager.extractSequential(solid7z, "02.jpg", password = null, lookahead = 0) { _, _ -> }
        assertTrue(ok2)

        // Request 00.jpg with allowRewind = false (simulating background sweep)
        var callbackCalled = false
        val ok0 = sessionManager.extractSequential(solid7z, "00.jpg", password = null, lookahead = 0, allowRewind = false) { _, _ ->
            callbackCalled = true
        }

        assertFalse("Should not rewind or succeed for backward target when allowRewind=false", ok0)
        assertFalse("Callback should not be invoked", callbackCalled)

        // The session cursor should remain at 02.jpg, so forward request to 03.jpg continues forward seamlessly
        var extracted03 = false
        val ok3 = sessionManager.extractSequential(solid7z, "03.jpg", password = null, lookahead = 0) { name, _ ->
            if (name == "03.jpg") extracted03 = true
        }
        assertTrue(ok3)
        assertTrue(extracted03)
    }

    @Test
    fun `extractThumbnailResult with allowRewind=false skips backward target without rewinding`() = runBlocking {
        val thumbDir = tempFolder.newFolder("rewind_thumb_dir")
        val thumbCache = ThumbnailDiskCache(thumbDir)

        // Advance to 02.jpg
        val thumb2 = sessionManager.extractThumbnailResult(
            file = solid7z,
            targetEntryName = "02.jpg",
            targetSizePx = 100,
            password = null,
            thumbnailDiskCache = thumbCache,
            allowRewind = true
        )
        assertNotNull(thumb2)

        // Request 01.jpg with allowRewind = false
        val thumb1 = sessionManager.extractThumbnailResult(
            file = solid7z,
            targetEntryName = "01.jpg",
            targetSizePx = 100,
            password = null,
            thumbnailDiskCache = thumbCache,
            allowRewind = false
        )
        assertNull("Should return null and not rewind when allowRewind=false", thumb1)
    }
}
