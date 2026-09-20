package com.watchpicture.app.archive

import com.watchpicture.app.security.SessionPasswordStore
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

class ArchiveFileResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var plainZip: File
    private lateinit var cbzFile: File
    private lateinit var aesZip: File
    private lateinit var textFile: File
    private lateinit var emptyFile: File
    private val password = "ArchiveTestPassword123"
    private val testImageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)

    private val zipArchiveManager = ZipArchiveManager()
    private val passwordStore = SessionPasswordStore()
    private lateinit var resolver: ArchiveFileResolver

    @Before
    fun setUp() {
        resolver = ArchiveFileResolver(zipArchiveManager, passwordStore)

        // 1. Plain ZIP with multiple images and non-image files
        plainZip = tempFolder.newFile("sample_pack.zip")
        ZipFile(plainZip).use { zip ->
            val p1 = ZipParameters().apply { fileNameInZip = "02_second.jpg" }
            zip.addStream(ByteArrayInputStream(testImageBytes), p1)
            val p2 = ZipParameters().apply { fileNameInZip = "01_first.jpg" }
            zip.addStream(ByteArrayInputStream(testImageBytes), p2)
            val p3 = ZipParameters().apply { fileNameInZip = "info.txt" }
            zip.addStream(ByteArrayInputStream("ignored".toByteArray()), p3)
        }

        // 2. CBZ file
        cbzFile = tempFolder.newFile("manga_vol1.cbz")
        ZipFile(cbzFile).use { zip ->
            val p = ZipParameters().apply { fileNameInZip = "page_01.png" }
            zip.addStream(ByteArrayInputStream(testImageBytes), p)
        }

        // 3. AES encrypted ZIP
        aesZip = tempFolder.newFile("protected.zip")
        ZipFile(aesZip, password.toCharArray()).use { zip ->
            val p = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                fileNameInZip = "secret_cover.webp"
            }
            zip.addStream(ByteArrayInputStream(testImageBytes), p)
        }

        // 4. Non-ZIP text file
        textFile = tempFolder.newFile("not_a_zip.txt")
        FileOutputStream(textFile).use { it.write("Just some random text content".toByteArray()) }

        // 5. Empty file (0 bytes)
        emptyFile = tempFolder.newFile("empty.zip")
    }

    @Test
    fun `isValidZipArchive correctly verifies magic bytes`() {
        assertTrue("Standard ZIP should be recognized", resolver.isValidZipArchive(plainZip))
        assertTrue("CBZ archive should be recognized", resolver.isValidZipArchive(cbzFile))
        assertTrue("Encrypted ZIP should be recognized", resolver.isValidZipArchive(aesZip))

        assertFalse("Text file must not be recognized as ZIP", resolver.isValidZipArchive(textFile))
        assertFalse("Empty file must not be recognized as ZIP", resolver.isValidZipArchive(emptyFile))
    }

    @Test
    fun `createZipPackFromFile successfully constructs pack model for plain archive`() {
        val pack = resolver.createZipPackFromFile(plainZip)
        assertNotNull("ZipPack should not be null", pack)
        pack!!

        assertEquals(plainZip.absolutePath, pack.id)
        assertEquals("sample_pack", pack.name)
        assertEquals(2, pack.itemCount) // 2 images, info.txt filtered
        assertFalse(pack.isEncrypted)
        assertFalse(pack.isCbz)
        assertEquals(plainZip.absolutePath, pack.directPath)
        assertNotNull(pack.coverImage)
        // Natural order: 01_first.jpg should be the cover
        assertEquals("01_first.jpg", pack.coverImage?.entryPath)
    }

    @Test
    fun `createZipPackFromFile identifies CBZ archive correctly`() {
        val pack = resolver.createZipPackFromFile(cbzFile)
        assertNotNull(pack)
        pack!!

        assertEquals("manga_vol1", pack.name)
        assertTrue(pack.isCbz)
        assertEquals(1, pack.itemCount)
    }

    @Test
    fun `createZipPackFromFile identifies encrypted archive`() {
        val pack = resolver.createZipPackFromFile(aesZip)
        assertNotNull(pack)
        pack!!

        assertEquals("protected", pack.name)
        assertTrue(pack.isEncrypted)
        assertEquals(1, pack.itemCount)
    }

    @Test
    fun `createZipPackFromFile returns null for invalid file`() {
        val pack = resolver.createZipPackFromFile(textFile)
        assertNull("Invalid ZIP should return null", pack)
    }

    @Test
    fun `handles case insensitive extensions correctly`() {
        val upperZip = tempFolder.newFile("UPPERCASE_PACK.ZIP")
        ZipFile(upperZip).use { zip ->
            val p = ZipParameters().apply { fileNameInZip = "item.png" }
            zip.addStream(ByteArrayInputStream(testImageBytes), p)
        }

        val pack = resolver.createZipPackFromFile(upperZip)
        assertNotNull(pack)
        pack!!
        assertEquals("UPPERCASE_PACK", pack.name)
        assertEquals(1, pack.itemCount)
        assertFalse(pack.isCbz)
    }

    @Test
    fun `isValidZipArchive handles truncated or false magic prefix`() {
        val truncatedFile = tempFolder.newFile("truncated.bin")
        FileOutputStream(truncatedFile).use { it.write(byteArrayOf(0x50, 0x4B)) } // only 2 bytes

        assertFalse(resolver.isValidZipArchive(truncatedFile))

        val fakeMagicFile = tempFolder.newFile("fake_magic.bin")
        FileOutputStream(fakeMagicFile).use { it.write(byteArrayOf(0x50, 0x4B, 0x01, 0x02)) } // PK\x01\x02 is central directory, not file header
        assertFalse(resolver.isValidZipArchive(fakeMagicFile))
    }

    @Test
    fun `rejects apk files and disguised apk archives with android manifest`() {
        // 1. Standard .apk file with embedded images
        val apkFile = tempFolder.newFile("demo_app.apk")
        ZipFile(apkFile).use { zip ->
            val pManifest = ZipParameters().apply { fileNameInZip = "AndroidManifest.xml" }
            zip.addStream(ByteArrayInputStream("<manifest/>".toByteArray()), pManifest)
            val pIcon = ZipParameters().apply { fileNameInZip = "res/drawable/icon.png" }
            zip.addStream(ByteArrayInputStream(testImageBytes), pIcon)
        }

        assertNull("Direct .apk file must not be resolved as picture pack", resolver.createZipPackFromFile(apkFile))

        // 2. APK renamed to .zip (disguised package containing AndroidManifest.xml)
        val disguisedZip = tempFolder.newFile("disguised_app.zip")
        ZipFile(disguisedZip).use { zip ->
            val pManifest = ZipParameters().apply { fileNameInZip = "AndroidManifest.xml" }
            zip.addStream(ByteArrayInputStream("<manifest/>".toByteArray()), pManifest)
            val pDex = ZipParameters().apply { fileNameInZip = "classes.dex" }
            zip.addStream(ByteArrayInputStream(byteArrayOf(0x64, 0x65, 0x78, 0x0A)), pDex)
            val pIcon = ZipParameters().apply { fileNameInZip = "assets/pic.jpg" }
            zip.addStream(ByteArrayInputStream(testImageBytes), pIcon)
        }

        assertNull("Disguised APK containing AndroidManifest.xml must be rejected", resolver.createZipPackFromFile(disguisedZip))

        // 3. Android library archive (.aar) or java archive (.jar)
        val jarFile = tempFolder.newFile("library.jar")
        ZipFile(jarFile).use { zip ->
            val pMeta = ZipParameters().apply { fileNameInZip = "META-INF/MANIFEST.MF" }
            zip.addStream(ByteArrayInputStream("Manifest-Version: 1.0".toByteArray()), pMeta)
            val pIcon = ZipParameters().apply { fileNameInZip = "logo.png" }
            zip.addStream(ByteArrayInputStream(testImageBytes), pIcon)
        }

        assertNull("Jar or AAR archives must not be resolved as picture pack", resolver.createZipPackFromFile(jarFile))
    }
}
