package com.watchpicture.app.coil

import coil3.decode.DataSource
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
    fun `fetches encrypted entry as seekable file source and cleans up on close`() = runBlocking {
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
        assertEquals(DataSource.DISK, sourceResult.dataSource)

        // Must provide a concrete Path so BitmapFactoryDecoder can decode without stream exhaustion
        val filePath = sourceResult.source.fileOrNull()
        assertNotNull("ImageSource must have a non-null file path for seekable decoding", filePath)
        val tempFile = File(filePath.toString())
        assertTrue("Temporary file must exist during decoding", tempFile.exists())

        // Validate content integrity from underlying file
        assertArrayEquals("File content must match original bytes", sampleBytes, tempFile.readBytes())

        // Validate peek without consuming (same mechanism used by BitmapFactoryDecoder)
        val peekBytes = sourceResult.source.source().peek().readByteArray()
        assertArrayEquals("Peeked bytes must match original bytes", sampleBytes, peekBytes)

        // Validate consumption
        val readBytes = sourceResult.source.source().readByteArray()
        assertArrayEquals("Consumed bytes must match original bytes", sampleBytes, readBytes)

        // Close and verify automatic deletion
        sourceResult.source.close()
        assertFalse("Temporary file must be deleted on close", tempFile.exists())
    }
}
