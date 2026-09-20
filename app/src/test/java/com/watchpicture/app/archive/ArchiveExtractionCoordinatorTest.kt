package com.watchpicture.app.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveExtractionCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var archiveDiskCache: ArchiveDiskCache
    private lateinit var zipManager: ZipArchiveManager
    private lateinit var solid7z: File

    private val testBytes0 = "Page0Content".toByteArray()
    private val testBytes1 = "Page1Content".toByteArray()
    private val testBytes2 = "Page2Content".toByteArray()

    @Before
    fun setUp() {
        cacheDir = tempFolder.newFolder("archive_cache")
        archiveDiskCache = ArchiveDiskCache(cacheDir)
        zipManager = ZipArchiveManager()

        // Create a 7z solid archive with 3 sequential pages
        solid7z = tempFolder.newFile("gallery.7z")
        SevenZOutputFile(solid7z).use { out ->
            val e0 = out.createArchiveEntry(tempFolder.newFile("t0"), "00_page.jpg").apply { size = testBytes0.size.toLong() }
            out.putArchiveEntry(e0)
            out.write(testBytes0)
            out.closeArchiveEntry()

            val e1 = out.createArchiveEntry(tempFolder.newFile("t1"), "01_page.jpg").apply { size = testBytes1.size.toLong() }
            out.putArchiveEntry(e1)
            out.write(testBytes1)
            out.closeArchiveEntry()

            val e2 = out.createArchiveEntry(tempFolder.newFile("t2"), "02_page.jpg").apply { size = testBytes2.size.toLong() }
            out.putArchiveEntry(e2)
            out.write(testBytes2)
            out.closeArchiveEntry()
        }
    }

    @Test
    fun `extractHighPriority extracts target entry and writes to disk cache`() = runBlocking {
        val coordinator = ArchiveExtractionCoordinator(zipManager, archiveDiskCache)

        val file = coordinator.extractHighPriority(solid7z, "01_page.jpg", password = null)

        assertNotNull(file)
        assertTrue(file.exists())
        assertArrayEquals(testBytes1, file.readBytes())

        // Cache must have recorded this entry
        val fromCache = archiveDiskCache.get(solid7z, "01_page.jpg", password = null)
        assertNotNull(fromCache)
        assertEquals(file.absolutePath, fromCache?.absolutePath)
    }

    @Test
    fun `extractHighPriority on solid 7z opportunistically caches prior entries`() = runBlocking {
        val coordinator = ArchiveExtractionCoordinator(zipManager, archiveDiskCache)

        // Requesting 02_page.jpg scans past 00_page.jpg and 01_page.jpg
        val file2 = coordinator.extractHighPriority(solid7z, "02_page.jpg", password = null)
        assertArrayEquals(testBytes2, file2.readBytes())

        // 00_page.jpg and 01_page.jpg must have been opportunistically cached to disk!
        val cached0 = archiveDiskCache.get(solid7z, "00_page.jpg", password = null)
        val cached1 = archiveDiskCache.get(solid7z, "01_page.jpg", password = null)

        assertNotNull("Prior entry 00_page.jpg should be opportunistically cached", cached0)
        assertNotNull("Prior entry 01_page.jpg should be opportunistically cached", cached1)
        assertArrayEquals(testBytes0, cached0?.readBytes())
        assertArrayEquals(testBytes1, cached1?.readBytes())
    }

    @Test
    fun `concurrent extractions for the same archive succeed without deadlocks`() = runBlocking {
        val coordinator = ArchiveExtractionCoordinator(zipManager, archiveDiskCache)

        // Launch concurrent high-priority requests for different entries of the same archive
        val deferreds = listOf("00_page.jpg", "01_page.jpg", "02_page.jpg").map { entryName ->
            async(Dispatchers.IO) {
                coordinator.extractHighPriority(solid7z, entryName, password = null)
            }
        }

        val results = deferreds.awaitAll()
        assertEquals(3, results.size)
        assertTrue(results.all { it.exists() && it.length() > 0 })
    }

    @Test
    fun `prefetch drops background extraction when powerThermalManager is throttled`() = runBlocking {
        val thermalManager = PowerThermalManager().apply {
            setManualThrottleLevel(PowerThermalManager.ThrottleLevel.THROTTLED)
        }
        val coordinator = ArchiveExtractionCoordinator(zipManager, archiveDiskCache, thermalManager)

        // Request prefetch when throttled
        coordinator.prefetch(solid7z, listOf("00_page.jpg"), password = null)

        // Must NOT have cached 00_page.jpg because prefetch was aborted due to thermal throttle
        val cached = archiveDiskCache.get(solid7z, "00_page.jpg", password = null)
        assertNull("Prefetch should be dropped when thermal manager is throttled", cached)
    }
}
