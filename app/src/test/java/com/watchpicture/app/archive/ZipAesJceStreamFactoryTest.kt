package com.watchpicture.app.archive

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Random

/**
 * E1 互操作验证：以 Zip4j 写端（WinZip AE 规范兼容）为样本源，
 * 验证 JCE 硬件加速解密管线的正确性（CTR / PBKDF2 / HMAC / password verifier）。
 */
class ZipAesJceStreamFactoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val password = "p@ssw0rd-测试"
    private val payload = "hello watchpicture aes jce".toByteArray()

    @Before
    fun resetKeyCache() {
        ZipAesKeyCache.clear()
    }

    private fun createAesZip(
        fileName: String,
        entryName: String,
        entryBytes: ByteArray,
        strength: AesKeyStrength = AesKeyStrength.KEY_STRENGTH_256,
        compressionMethod: CompressionMethod = CompressionMethod.DEFLATE,
        encryptionMethod: EncryptionMethod = EncryptionMethod.AES,
        entryPassword: String = password
    ): File {
        val target = tempFolder.newFile(fileName)
        ZipFile(target).use { zip ->
            zip.setPassword(entryPassword.toCharArray())
            val params = ZipParameters()
            params.fileNameInZip = entryName
            params.isEncryptFiles = true
            params.encryptionMethod = encryptionMethod
            params.aesKeyStrength = strength
            params.compressionMethod = compressionMethod
            zip.addStream(ByteArrayInputStream(entryBytes), params)
        }
        return target
    }

    private fun readHeader(file: File, entryName: String): FileHeader {
        ZipFile(file).use { zip ->
            return zip.getFileHeader(entryName) ?: zip.fileHeaders.first()
        }
    }

    private fun readAll(factory: ZipAesJceStreamFactory, file: File, header: FileHeader): ByteArray {
        val stream = factory.open(file, header, password.toCharArray())
        assertNotNull("AES 条目应返回流而非 null", stream)
        return stream!!.use { it.readBytes() }
    }

    @Test
    fun `aes-256 deflate 往返一致`() {
        val zip = createAesZip("a256def.zip", "img/001.png", payload)
        val header = readHeader(zip, "img/001.png")
        assertEquals(EncryptionMethod.AES, header.encryptionMethod)

        val factory = ZipAesJceStreamFactory()
        assertArrayEquals(payload, readAll(factory, zip, header))
    }

    @Test
    fun `aes-128 store 往返一致`() {
        val zip = createAesZip(
            "a128store.zip", "002.jpg", payload,
            strength = AesKeyStrength.KEY_STRENGTH_128,
            compressionMethod = CompressionMethod.STORE
        )
        val header = readHeader(zip, "002.jpg")

        val factory = ZipAesJceStreamFactory()
        assertArrayEquals(payload, readAll(factory, zip, header))
    }

    @Test
    fun `大于1MB随机数据往返一致（跨块计数器进位）`() {
        val random = Random(42)
        val bigPayload = ByteArray((1 shl 20) + 137).also { random.nextBytes(it) }
        val zip = createAesZip("big.zip", "big/001.jpg", bigPayload)
        val header = readHeader(zip, "big/001.jpg")

        val factory = ZipAesJceStreamFactory()
        assertArrayEquals(bigPayload, readAll(factory, zip, header))
    }

    @Test
    fun `中文名条目往返一致`() {
        val entryName = "图片/中文 命名 001.png"
        val zip = createAesZip("gbk.zip", entryName, payload)
        val header = readHeader(zip, entryName)

        val factory = ZipAesJceStreamFactory()
        assertArrayEquals(payload, readAll(factory, zip, header))
    }

    @Test
    fun `错误密码抛 WRONG_PASSWORD`() {
        val zip = createAesZip("wrongpw.zip", "001.png", payload)
        val header = readHeader(zip, "001.png")

        val factory = ZipAesJceStreamFactory()
        try {
            factory.open(zip, header, "definitely-wrong".toCharArray()).use { it?.read() }
            fail("错误密码应抛 ZipException(WRONG_PASSWORD)")
        } catch (e: ZipException) {
            assertEquals(ZipException.Type.WRONG_PASSWORD, e.type)
        }
    }

    @Test
    fun `密文篡改在流末尾触发 HMAC 校验失败`() {
        val zip = createAesZip("tamper.zip", "001.png", payload)
        val header = readHeader(zip, "001.png")

        // 定位密文首字节：local header 之后跳过 salt(16) + verifier(2)
        val dataStart = entryDataStart(zip, header)
        RandomAccessFile(zip, "rw").use { raf ->
            raf.seek(dataStart + 16 + 2)
            val b = raf.read()
            raf.seek(dataStart + 16 + 2)
            raf.write(b xor 0x01)
        }

        val factory = ZipAesJceStreamFactory()
        try {
            factory.open(zip, header, password.toCharArray()).use { it!!.readBytes() }
            fail("密文被篡改应在流末尾抛出 IOException")
        } catch (_: java.io.IOException) {
            // ZipException extends IOException：HMAC 不匹配在此即验证通过
        }
    }

    @Test
    fun `非AES加密条目返回null交回调用方`() {
        val zip = createAesZip(
            "zipcrypto.zip", "001.png", payload,
            encryptionMethod = EncryptionMethod.ZIP_STANDARD,
            strength = AesKeyStrength.KEY_STRENGTH_256
        )
        val header = readHeader(zip, "001.png")
        assertEquals(EncryptionMethod.ZIP_STANDARD, header.encryptionMethod)

        val factory = ZipAesJceStreamFactory()
        assertNull(factory.open(zip, header, password.toCharArray()))
    }

    @Test
    fun `未加密条目返回null`() {
        val target = tempFolder.newFile("plain.zip")
        ZipFile(target).use { zip ->
            zip.addStream(ByteArrayInputStream(payload), ZipParameters().apply { fileNameInZip = "001.png" })
        }
        val header = readHeader(target, "001.png")

        val factory = ZipAesJceStreamFactory()
        assertNull(factory.open(target, header, password.toCharArray()))
    }

    @Test
    fun `重复派生同盐命中密钥缓存`() {
        val zip = createAesZip("cache.zip", "001.png", payload)
        val header = readHeader(zip, "001.png")
        val factory = ZipAesJceStreamFactory()

        assertArrayEquals(payload, readAll(factory, zip, header))
        assertEquals(1, ZipAesKeyCache.size)
        assertArrayEquals(payload, readAll(factory, zip, header))
        assertEquals(1, ZipAesKeyCache.size)
    }

    @Test
    fun `句柄池加密路径读取AES条目`() {
        val zip = createAesZip("pool.zip", "img/001.png", payload)
        val pool = ArchiveHandlePool()

        val stream = pool.openEncryptedEntryStream(zip, "img/001.png", password)
        assertNotNull(stream)
        assertArrayEquals(payload, stream!!.use { it.readBytes() })
        pool.closeAll()
    }

    @Test
    fun `句柄池错误密码抛 ZipException`() {
        val zip = createAesZip("poolwrong.zip", "001.png", payload)
        val pool = ArchiveHandlePool()

        try {
            pool.openEncryptedEntryStream(zip, "001.png", "wrong".repeat(3)).use { it?.read() }
            fail("错误密码应抛 ZipException")
        } catch (_: ZipException) {
            // expected
        }
        pool.closeAll()
    }

    @Test
    fun `句柄池 ZipCrypto 条目回归正确`() {
        val zip = createAesZip(
            "poolzipcrypto.zip", "001.png", payload,
            encryptionMethod = EncryptionMethod.ZIP_STANDARD,
            strength = AesKeyStrength.KEY_STRENGTH_256
        )
        val pool = ArchiveHandlePool()

        val stream = pool.openEncryptedEntryStream(zip, "001.png", password)
        assertNotNull(stream)
        assertArrayEquals(payload, stream!!.use { it.readBytes() })
        pool.closeAll()
    }

    @Test
    fun `ArchiveManager 入口读取AES条目`() {
        val zip = createAesZip("manager.zip", "img/001.png", payload)
        val manager = ZipArchiveManager()

        val stream = manager.getEntryInputStream(zip, "img/001.png", password)
        assertArrayEquals(payload, stream.use { it.readBytes() })
    }

    @Test
    fun `ArchiveManager 大文件 AES 条目完整性`() {
        val random = Random(7)
        val bigPayload = ByteArray(2 shl 20).also { random.nextBytes(it) }
        val zip = createAesZip("managerbig.zip", "big/001.jpg", bigPayload)
        val manager = ZipArchiveManager()

        val stream = manager.getEntryInputStream(zip, "big/001.jpg", password)
        assertArrayEquals(bigPayload, stream.use { it.readBytes() })
    }

    private fun entryDataStart(file: File, header: FileHeader): Long {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(header.offsetLocalHeader)
            val head = ByteArray(30)
            raf.readFully(head)
            if (readLeInt(head, 0) != 0x04034b50) throw IllegalStateException("bad local header")
            val nameLen = readLeShort(head, 26)
            val extraLen = readLeShort(head, 28)
            return header.offsetLocalHeader + 30 + nameLen + extraLen
        }
    }

    private fun readLeShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readLeInt(b: ByteArray, off: Int): Int =
        readLeShort(b, off) or (readLeShort(b, off + 2) shl 16)
}
