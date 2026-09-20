package com.watchpicture.app.coil

import coil3.request.Options
import com.watchpicture.app.archive.ArchiveDiskCache
import com.watchpicture.app.archive.ThumbnailDiskCache
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ZipImageMapperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var archiveCacheDir: File
    private lateinit var thumbCacheDir: File
    private lateinit var archiveDiskCache: ArchiveDiskCache
    private lateinit var dummyZip: File

    @Before
    fun setUp() {
        archiveCacheDir = tempFolder.newFolder("archive_cache")
        thumbCacheDir = tempFolder.newFolder("thumb_cache")
        archiveDiskCache = ArchiveDiskCache(archiveCacheDir)
        dummyZip = tempFolder.newFile("test.zip")
    }

    @Test
    fun `returns null when entry is not yet cached on disk`() {
        val mapper = ZipImageMapper(archiveDiskCache)
        val data = ZipImageSource(
            zipFile = dummyZip,
            entryName = "photo.jpg",
            password = null
        )
        // Dummy options (unused by mapper)
        val options = Options(android.content.ContextWrapper(null))

        val result = mapper.map(data, options)
        assertNull("Should return null when entry is not extracted to disk cache", result)
    }

    @Test
    fun `returns File when full-res entry is cached in ArchiveDiskCache`() {
        val mapper = ZipImageMapper(archiveDiskCache)
        val data = ZipImageSource(
            zipFile = dummyZip,
            entryName = "photo.jpg",
            password = null
        )

        // Seed disk cache
        val cachedFile = archiveDiskCache.getOrPut(dummyZip, "photo.jpg", null) {
            ByteArrayInputStream("FakeImageData".toByteArray())
        }

        val options = Options(android.content.ContextWrapper(null))
        val result = mapper.map(data, options)

        assertNotNull("Should map to File when cached on disk", result)
        assertEquals(cachedFile.absolutePath, result?.absolutePath)
    }
}
