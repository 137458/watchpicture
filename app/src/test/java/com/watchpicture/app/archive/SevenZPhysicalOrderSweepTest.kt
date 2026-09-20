package com.watchpicture.app.archive

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SevenZPhysicalOrderSweepTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var thumbDir: File
    private lateinit var archiveDiskCache: ArchiveDiskCache
    private lateinit var thumbnailDiskCache: ThumbnailDiskCache
    private lateinit var zipManager: ZipArchiveManager
    private lateinit var coordinator: ArchiveExtractionCoordinator
    private lateinit var testArchive: File

    private val payload1 = "1.jpg content".toByteArray()
    private val payload10 = "10.jpg content".toByteArray()
    private val payload2 = "2.jpg content".toByteArray()

    @Before
    fun setUp() {
        cacheDir = tempFolder.newFolder("sweep_cache")
        thumbDir = tempFolder.newFolder("sweep_thumb")
        archiveDiskCache = ArchiveDiskCache(cacheDir)
        thumbnailDiskCache = ThumbnailDiskCache(thumbDir)
        zipManager = ZipArchiveManager()
        coordinator = ArchiveExtractionCoordinator(zipManager, archiveDiskCache)

        // Create archive with PHYSICAL entry order:
        // index 0: 1.jpg
        // index 1: 10.jpg
        // index 2: 2.jpg
        testArchive = tempFolder.newFile("disordered.7z")
        SevenZOutputFile(testArchive).use { out ->
            val e0 = out.createArchiveEntry(tempFolder.newFile("f1"), "1.jpg").apply { size = payload1.size.toLong() }
            out.putArchiveEntry(e0)
            out.write(payload1)
            out.closeArchiveEntry()

            val e1 = out.createArchiveEntry(tempFolder.newFile("f10"), "10.jpg").apply { size = payload10.size.toLong() }
            out.putArchiveEntry(e1)
            out.write(payload10)
            out.closeArchiveEntry()

            val e2 = out.createArchiveEntry(tempFolder.newFile("f2"), "2.jpg").apply { size = payload2.size.toLong() }
            out.putArchiveEntry(e2)
            out.write(payload2)
            out.closeArchiveEntry()
        }
    }

    @Test
    fun `startBatchThumbnailSweep must extract all entries even when passed in natural order`() = runBlocking {
        // UI Natural order: 1.jpg -> 2.jpg -> 10.jpg
        // Notice: 2.jpg is at physical index 2, while 10.jpg is at physical index 1.
        val naturalOrderTargets = listOf("1.jpg", "2.jpg", "10.jpg")

        coordinator.startBatchThumbnailSweep(
            file = testArchive,
            entryNames = naturalOrderTargets,
            targetSizePx = 100,
            password = null,
            thumbnailDiskCache = thumbnailDiskCache
        )

        // All 3 entries MUST be cached in thumbnailDiskCache, NONE may be skipped!
        val thumb1 = thumbnailDiskCache.get(testArchive, "1.jpg", 100, null)
        val thumb2 = thumbnailDiskCache.get(testArchive, "2.jpg", 100, null)
        val thumb10 = thumbnailDiskCache.get(testArchive, "10.jpg", 100, null)

        assertNotNull("1.jpg thumbnail must exist", thumb1)
        assertNotNull("2.jpg thumbnail must exist", thumb2)
        assertNotNull("10.jpg thumbnail must exist (not skipped due to physical order mismatch!)", thumb10)
    }

    @Test
    fun `session recovers cleanly and allows subsequent reads after an unexpected cancellation`() = runBlocking {
        val sessionManager = coordinator.sevenZSessionManager

        // Trigger an intentional cancellation/interruption during an extraction
        val exceptionCaught = runCatching {
            sessionManager.extractSequential(testArchive, "1.jpg", password = null, lookahead = 0) { _, _ ->
                throw kotlinx.coroutines.CancellationException("Simulated view scroll cancellation")
            }
        }
        assertTrue("Cancellation should have been thrown", exceptionCaught.isFailure)

        // Subsequent extraction on the same session MUST succeed cleanly without stream corruption
        var readSuccess = false
        val ok = sessionManager.extractSequential(testArchive, "2.jpg", password = null, lookahead = 0) { name, _ ->
            if (name == "2.jpg") readSuccess = true
        }

        assertTrue("Extraction after cancellation must succeed", ok)
        assertTrue("Callback must be executed for 2.jpg", readSuccess)
    }

    @Test
    fun `startBatchThumbnailSweep must not drop preceding entries when session cursor was already advanced`() = runBlocking {
        val sessionManager = coordinator.sevenZSessionManager

        // Simulate user scrolling/jumping ahead: extract 2.jpg (physical index 2) first
        sessionManager.extractSequential(testArchive, "2.jpg", password = null, lookahead = 0) { _, _ -> }

        // At this point, 1.jpg and 10.jpg are NOT in thumbnailDiskCache, but cursor is at 2
        assertNull(thumbnailDiskCache.get(testArchive, "1.jpg", 100, null))
        assertNull(thumbnailDiskCache.get(testArchive, "10.jpg", 100, null))

        // Run batch sweep
        coordinator.startBatchThumbnailSweep(
            file = testArchive,
            entryNames = listOf("1.jpg", "10.jpg", "2.jpg"),
            targetSizePx = 100,
            password = null,
            thumbnailDiskCache = thumbnailDiskCache
        )

        // 1.jpg and 10.jpg MUST be extracted and cached, never dropped!
        val thumb1 = thumbnailDiskCache.get(testArchive, "1.jpg", 100, null)
        val thumb10 = thumbnailDiskCache.get(testArchive, "10.jpg", 100, null)

        assertNotNull("1.jpg must not be dropped when cursor was advanced", thumb1)
        assertNotNull("10.jpg must not be dropped when cursor was advanced", thumb10)
    }
}
