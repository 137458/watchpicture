package com.watchpicture.app.coil

import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.watchpicture.app.archive.ZipArchiveManager
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import okio.FileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ZipImageFetcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var encryptedZip: File
    private val password = "SecretPassword@999"
    private val sampleBytes = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
    private val entryName = "photos/cover.jpg"

    @Before
    fun setUp() {
        encryptedZip = tempFolder.newFile("encrypted.zip")
        ZipFile(encryptedZip, password.toCharArray()).use { zip ->
            val p = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                fileNameInZip = entryName
            }
            zip.addStream(ByteArrayInputStream(sampleBytes), p)
        }
    }

    @Test
    fun `fetches encrypted entry as memory buffer without writing to disk`() = runBlocking {
        val manager = ZipArchiveManager()
        val data = ZipImageSource(
            zipFile = encryptedZip,
            entryName = entryName,
            password = password
        )

        val cacheDir = tempFolder.newFolder("mock_cache")
        val options = Options(
            context = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): File = cacheDir
            },
            fileSystem = FileSystem.SYSTEM
        )

        val fetcher = ZipImageFetcher(data, options, manager)
        val result = fetcher.fetch()

        assertTrue("Result must be SourceFetchResult", result is SourceFetchResult)
        val sourceResult = result as SourceFetchResult
        assertEquals("image/jpeg", sourceResult.mimeType)
        assertEquals("Must be in-memory data source for zero disk I/O", DataSource.MEMORY, sourceResult.dataSource)

        // Zero temp files created in cacheDir
        val filesInCache = cacheDir.listFiles() ?: emptyArray()
        assertTrue("Cache dir must remain empty without temporary disk files", filesInCache.isEmpty())

        // Validate peek without consuming
        val peekBytes = sourceResult.source.source().peek().readByteArray()
        assertArrayEquals("Peeked bytes must match original bytes", sampleBytes, peekBytes)

        // Validate consumption
        val readBytes = sourceResult.source.source().readByteArray()
        assertArrayEquals("Consumed bytes must match original bytes", sampleBytes, readBytes)

        // Close cleanly
        sourceResult.source.close()
    }

    @Test
    fun `resolves extended mime types correctly`() = runBlocking {
        val manager = ZipArchiveManager()
        val testCases = mapOf(
            "photo.jfif" to "image/jpeg",
            "photo.pjpeg" to "image/jpeg",
            "photo.pjp" to "image/jpeg",
            "vector.svg" to "image/svg+xml",
            "scan.tiff" to "image/tiff",
            "scan.tif" to "image/tiff",
            "fav.ico" to "image/x-icon",
            "photo.avif" to "image/avif"
        )

        for ((fileName, expectedMime) in testCases) {
            val data = ZipImageSource(encryptedZip, fileName, password)
            val options = Options(
                context = object : android.content.ContextWrapper(null) {},
                fileSystem = FileSystem.SYSTEM
            )
            val fetcher = ZipImageFetcher(data, options, manager)
            // Use reflection or resolveMimeType to test MIME mapping
            val method = ZipImageFetcher::class.java.getDeclaredMethod("resolveMimeType", String::class.java).apply {
                isAccessible = true
            }
            val mime = method.invoke(fetcher, fileName)
            assertEquals("MIME mismatch for $fileName", expectedMime, mime)
        }
    }

    @Test
    fun `fetches entry via ArchiveDiskCache as file source with DataSource DISK`() = runBlocking {
        val manager = ZipArchiveManager()
        val data = ZipImageSource(
            zipFile = encryptedZip,
            entryName = entryName,
            password = password
        )

        val diskCacheDir = tempFolder.newFolder("archive_disk_cache")
        val archiveDiskCache = com.watchpicture.app.archive.ArchiveDiskCache(diskCacheDir)

        val options = Options(
            context = object : android.content.ContextWrapper(null) {},
            fileSystem = FileSystem.SYSTEM
        )

        val fetcher = ZipImageFetcher(data, options, manager, archiveDiskCache)
        val result = fetcher.fetch()

        assertTrue("Result must be SourceFetchResult", result is SourceFetchResult)
        val sourceResult = result as SourceFetchResult
        assertEquals("image/jpeg", sourceResult.mimeType)
        assertEquals("Must be DISK data source when disk cache is active", DataSource.DISK, sourceResult.dataSource)

        assertNotNull("ImageSource must have a concrete file path for subsampling decoders", sourceResult.source.fileOrNull())

        val readBytes = sourceResult.source.source().readByteArray()
        assertArrayEquals("Streamed bytes from disk cache must match original", sampleBytes, readBytes)
        sourceResult.source.close()
    }
}
