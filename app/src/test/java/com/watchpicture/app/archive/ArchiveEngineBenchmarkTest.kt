package com.watchpicture.app.archive

import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.crypto.AesCipherUtil
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.Random
import java.util.zip.ZipFile as NativeZipFile

/**
 * 解压引擎层基准 harness（调研文档 docs/research/archive_extraction_engine_optimization.md 阶段 0 前置项）。
 *
 * 基准矩阵（对应调研 §7 方案矩阵 Z1 / E1 的验证基线）：
 *  1. 未加密 ZIP 条目提取：JVM 原生 ZipFile 对比 Zip4j（Z1 容器管线开销）；
 *  2. AES-256 DEFLATE 条目解密提取：Zip4j 对比 JCE 管线（E1，首读与热读分开报告）；
 *  3. AES-128 STORE 条目解密吞吐：Zip4j 对比 JCE 管线（无 inflate，纯解密吞吐）；
 *  4. PBKDF2-HMAC-SHA1 千轮派生：Zip4j 纯 Java 对比本项目 Mac 实现（每条目固定开销）。
 *
 * 纪律：
 *  - 每个用例先断言正确性（解压字节与源字节完全一致），再计时；
 *  - 计时结果仅 println 输出（行前缀 `[bench]`），不对耗时做任何断言，避免构建抖动误报；
 *    KDF 用例无吞吐语义，打印行省略 MB/s 字段；
 *  - 桌面 JVM 结果仅作相对参考（无 Conscrypt 硬件 AES/SHA 路径），实机验收须在真机运行。
 */
class ArchiveEngineBenchmarkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val password = "bench-password-测试"

    @Before
    fun resetKeyCache() {
        ZipAesKeyCache.clear()
    }

    @Test
    fun `未加密 ZIP 条目提取：JVM 原生 ZipFile 对比 Zip4j`() {
        val payload = ByteArray(SAMPLE_1MB_BYTES).also { Random(44).nextBytes(it) }
        val zip = createPlainDeflateZip("bench-plain-deflate.zip", PLAIN_ENTRY_NAME, payload)
        val header = readHeader(zip, PLAIN_ENTRY_NAME)

        // 正确性先行：两个引擎的解压字节都必须与源字节完全一致
        assertArrayEquals(payload, readViaNativeZipFile(zip, PLAIN_ENTRY_NAME))
        assertArrayEquals(payload, readViaZip4j(zip, header, null))

        val nativeZip = NativeZipFile(zip)
        try {
            val entry = nativeZip.getEntry(PLAIN_ENTRY_NAME)
            assertNotNull("JVM 原生 ZipFile 应能定位条目 $PLAIN_ENTRY_NAME", entry)
            // ZipFile 句柄跨迭代复用（中央目录只解析一次），贴近 ArchiveHandlePool 池化后的生产用法
            benchEntryReads(CASE_PLAIN, "java.util.zip.ZipFile", payload.size) {
                nativeZip.getInputStream(entry!!).use { it.readBytes() }
            }
        } finally {
            nativeZip.close()
        }

        benchZip4jEntryReads(zip, header, CASE_PLAIN, payload.size, null)
    }

    @Test
    fun `AES-256 DEFLATE 条目解密提取：Zip4j 对比 JCE 管线`() {
        val payload = ByteArray(SAMPLE_1MB_BYTES).also { Random(42).nextBytes(it) }
        val zip = createAesZip(
            "bench-aes256-deflate.zip", DEFLATE_ENTRY_NAME, payload,
            strength = AesKeyStrength.KEY_STRENGTH_256,
            compressionMethod = CompressionMethod.DEFLATE
        )
        val header = readHeader(zip, DEFLATE_ENTRY_NAME)
        assertEquals(EncryptionMethod.AES, header.encryptionMethod)
        val factory = ZipAesJceStreamFactory()

        // 正确性先行
        ZipAesKeyCache.clear()
        assertArrayEquals(payload, factory.open(zip, header, password.toCharArray())!!.use { it.readBytes() })
        assertArrayEquals(payload, readViaZip4j(zip, header, password.toCharArray()))

        // 首读：清空盐级密钥缓存后单独报告（含 PBKDF2 派生固定开销）
        ZipAesKeyCache.clear()
        benchFirstRead(CASE_AES256_DEFLATE, "JCE 管线（首读，含 PBKDF2）", payload.size) {
            factory.open(zip, header, password.toCharArray())!!.use { it.readBytes() }
        }

        // 热读：派生密钥命中 ZipAesKeyCache，纯解密 + inflate 吞吐
        benchEntryReads(CASE_AES256_DEFLATE, "JCE 管线（热读）", payload.size) {
            factory.open(zip, header, password.toCharArray())!!.use { it.readBytes() }
        }

        benchZip4jEntryReads(zip, header, CASE_AES256_DEFLATE, payload.size, password.toCharArray())
    }

    @Test
    fun `AES-128 STORE 条目解密吞吐：Zip4j 对比 JCE 管线`() {
        val payload = ByteArray(SAMPLE_2MB_BYTES).also { Random(43).nextBytes(it) }
        val zip = createAesZip(
            "bench-aes128-store.zip", STORE_ENTRY_NAME, payload,
            strength = AesKeyStrength.KEY_STRENGTH_128,
            compressionMethod = CompressionMethod.STORE
        )
        val header = readHeader(zip, STORE_ENTRY_NAME)
        assertEquals(EncryptionMethod.AES, header.encryptionMethod)
        val factory = ZipAesJceStreamFactory()

        // 正确性先行
        ZipAesKeyCache.clear()
        assertArrayEquals(payload, factory.open(zip, header, password.toCharArray())!!.use { it.readBytes() })
        assertArrayEquals(payload, readViaZip4j(zip, header, password.toCharArray()))

        // 首读：冷缓存，含 PBKDF2 派生固定开销
        ZipAesKeyCache.clear()
        benchFirstRead(CASE_AES128_STORE, "JCE 管线（首读，含 PBKDF2）", payload.size) {
            factory.open(zip, header, password.toCharArray())!!.use { it.readBytes() }
        }

        // 热读：无 inflate，纯解密吞吐
        benchEntryReads(CASE_AES128_STORE, "JCE 管线（热读）", payload.size) {
            factory.open(zip, header, password.toCharArray())!!.use { it.readBytes() }
        }

        benchZip4jEntryReads(zip, header, CASE_AES128_STORE, payload.size, password.toCharArray())
    }

    @Test
    fun `PBKDF2 千轮派生：Zip4j 纯 Java 对比本项目 Mac 实现`() {
        // KDF 对比用纯 ASCII 密码：排除 Zip4j（ISO-8859-1 / UTF-8 双模式）与本项目（固定 UTF-8）
        // 的口令编码方式差异带来的歧义
        val kdfPassword = KDF_PASSWORD.toCharArray()
        val salt = ByteArray(AesKeyStrength.KEY_STRENGTH_256.saltLength).also { Random(45).nextBytes(it) }

        // 正确性先行：同为标准 PBKDF2-HMAC-SHA1（1000 轮、同盐同密码），全量输出（AES 密钥 +
        // HMAC 密钥 + 2B verifier，两侧同口径 50B）必须逐字节一致。
        val zip4jDerived = AesCipherUtil.derivePasswordBasedKey(salt, kdfPassword, AesKeyStrength.KEY_STRENGTH_256, true)
        ZipAesKeyCache.clear()
        val projectDerived = ZipAesKeyCache.getOrDerive(kdfPassword, salt, AesKeyStrength.KEY_STRENGTH_256)
        assertEquals("两侧 PBKDF2 派生输出长度口径一致", zip4jDerived.size, projectDerived.size)
        assertArrayEquals(zip4jDerived, projectDerived)

        benchKdfDerivations(CASE_PBKDF2, "Zip4j 纯 Java PBKDF2") {
            AesCipherUtil.derivePasswordBasedKey(salt, kdfPassword, AesKeyStrength.KEY_STRENGTH_256, true)
        }
        benchKdfDerivations(CASE_PBKDF2, "本项目 Mac 手写 PBKDF2", beforeEach = { ZipAesKeyCache.clear() }) {
            ZipAesKeyCache.getOrDerive(kdfPassword, salt, AesKeyStrength.KEY_STRENGTH_256)
        }
    }

    // ---------- 样本构造 ----------

    private fun createPlainDeflateZip(fileName: String, entryName: String, entryBytes: ByteArray): File {
        val target = tempFolder.newFile(fileName)
        Zip4jFile(target).use { zip ->
            val params = ZipParameters()
            params.fileNameInZip = entryName
            params.compressionMethod = CompressionMethod.DEFLATE
            zip.addStream(ByteArrayInputStream(entryBytes), params)
        }
        return target
    }

    private fun createAesZip(
        fileName: String,
        entryName: String,
        entryBytes: ByteArray,
        strength: AesKeyStrength,
        compressionMethod: CompressionMethod
    ): File {
        val target = tempFolder.newFile(fileName)
        Zip4jFile(target).use { zip ->
            zip.setPassword(password.toCharArray())
            val params = ZipParameters()
            params.fileNameInZip = entryName
            params.isEncryptFiles = true
            params.encryptionMethod = EncryptionMethod.AES
            params.aesKeyStrength = strength
            params.compressionMethod = compressionMethod
            zip.addStream(ByteArrayInputStream(entryBytes), params)
        }
        return target
    }

    private fun readHeader(file: File, entryName: String): FileHeader {
        Zip4jFile(file).use { zip ->
            return zip.getFileHeader(entryName) ?: zip.fileHeaders.first()
        }
    }

    // ---------- 正确性读取 ----------

    private fun readViaNativeZipFile(file: File, entryName: String): ByteArray {
        NativeZipFile(file).use { zip ->
            val entry = zip.getEntry(entryName)
            assertNotNull("JVM 原生 ZipFile 应能定位条目 $entryName", entry)
            return zip.getInputStream(entry!!).use { it.readBytes() }
        }
    }

    private fun readViaZip4j(file: File, header: FileHeader, passwordChars: CharArray?): ByteArray =
        Zip4jFile(file).use { zip ->
            if (passwordChars != null) zip.setPassword(passwordChars)
            zip.getInputStream(header).use { it.readBytes() }
        }

    // ---------- 计时 ----------

    /** 预热 [WARMUP_RUNS] 次后计时 [TIMED_RUNS] 次，输出平均/最小耗时与按解压后字节计算的吞吐。 */
    private fun benchEntryReads(caseLabel: String, engineLabel: String, uncompressedBytes: Int, readOnce: () -> Unit) {
        repeat(WARMUP_RUNS) { readOnce() }
        var totalNanos = 0L
        var minNanos = Long.MAX_VALUE
        repeat(TIMED_RUNS) {
            val startNanos = System.nanoTime()
            readOnce()
            val elapsedNanos = System.nanoTime() - startNanos
            totalNanos += elapsedNanos
            if (elapsedNanos < minNanos) minNanos = elapsedNanos
        }
        println(
            String.format(
                Locale.US,
                "[bench] %s | %s | 平均 %.2f ms | 最小 %.2f ms | 吞吐 %.2f MB/s",
                caseLabel, engineLabel,
                totalNanos / 1_000_000.0 / TIMED_RUNS,
                minNanos / 1_000_000.0,
                mbPerSecond(uncompressedBytes, totalNanos / TIMED_RUNS)
            )
        )
    }

    /** 单次冷读计时（JCE 管线首读含 PBKDF2 派生，与热读分开报告）。 */
    private fun benchFirstRead(caseLabel: String, engineLabel: String, uncompressedBytes: Int, readOnce: () -> Unit) {
        val startNanos = System.nanoTime()
        readOnce()
        val elapsedNanos = System.nanoTime() - startNanos
        val millis = elapsedNanos / 1_000_000.0
        println(
            String.format(
                Locale.US,
                "[bench] %s | %s | 平均 %.2f ms | 最小 %.2f ms | 吞吐 %.2f MB/s",
                caseLabel, engineLabel, millis, millis, mbPerSecond(uncompressedBytes, elapsedNanos)
            )
        )
    }

    /** Zip4j 引擎条目读取计时：句柄跨迭代复用，与原生 ZipFile 的计时口径对齐。 */
    private fun benchZip4jEntryReads(
        file: File,
        header: FileHeader,
        caseLabel: String,
        uncompressedBytes: Int,
        passwordChars: CharArray?
    ) {
        val zip4j = Zip4jFile(file)
        try {
            if (passwordChars != null) zip4j.setPassword(passwordChars)
            benchEntryReads(caseLabel, "Zip4j", uncompressedBytes) {
                zip4j.getInputStream(header).use { it.readBytes() }
            }
        } finally {
            zip4j.close()
        }
    }

    /** KDF 派生计时（毫秒精度到小数点后 3 位；无吞吐语义，打印行省略 MB/s 字段）。 */
    private fun benchKdfDerivations(
        caseLabel: String,
        engineLabel: String,
        beforeEach: (() -> Unit)? = null,
        deriveOnce: () -> Unit
    ) {
        repeat(WARMUP_RUNS) {
            beforeEach?.invoke()
            deriveOnce()
        }
        var totalNanos = 0L
        var minNanos = Long.MAX_VALUE
        repeat(TIMED_RUNS) {
            beforeEach?.invoke()
            val startNanos = System.nanoTime()
            deriveOnce()
            val elapsedNanos = System.nanoTime() - startNanos
            totalNanos += elapsedNanos
            if (elapsedNanos < minNanos) minNanos = elapsedNanos
        }
        println(
            String.format(
                Locale.US,
                "[bench] %s | %s | 平均 %.3f ms | 最小 %.3f ms",
                caseLabel, engineLabel,
                totalNanos / 1_000_000.0 / TIMED_RUNS,
                minNanos / 1_000_000.0
            )
        )
    }

    private fun mbPerSecond(bytes: Int, nanos: Long): Double {
        if (nanos <= 0L) return 0.0
        return bytes / (1024.0 * 1024.0) / (nanos / 1_000_000_000.0)
    }

    private companion object {
        private const val WARMUP_RUNS = 2
        private const val TIMED_RUNS = 5
        private val SAMPLE_1MB_BYTES = 1 shl 20
        private val SAMPLE_2MB_BYTES = 2 shl 20

        private const val PLAIN_ENTRY_NAME = "bench/plain-001.png"
        private const val DEFLATE_ENTRY_NAME = "bench/deflate-001.jpg"
        private const val STORE_ENTRY_NAME = "bench/store-001.jpg"

        private const val CASE_PLAIN = "未加密 ZIP 条目提取"
        private const val CASE_AES256_DEFLATE = "AES-256 DEFLATE 条目解密提取"
        private const val CASE_AES128_STORE = "AES-128 STORE 条目解密吞吐"
        private const val CASE_PBKDF2 = "PBKDF2 千轮派生"

        /** KDF 对比专用纯 ASCII 密码：与两侧 PBKDF2 的口令编码方式（ISO-8859-1 / UTF-8）均无歧义。 */
        private const val KDF_PASSWORD = "kdf-bench-password-0123456789"
    }
}
