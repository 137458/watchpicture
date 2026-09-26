package com.watchpicture.app.archive

import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock

/**
 * WinZip AES 加密条目的 JCE 流式解密管线（调研 E1 项）。
 *
 * Zip4j 的 AES 内核为纯 Java 实现（逐 16 字节 processBlock + 逐字节 XOR + 逐块 mac.update，
 * PBKDF2 纯 Java 循环），实测吞吐仅 30~50MB/s。本管线将加密层整体替换为平台 Conscrypt
 * （BoringSSL ARMv8 硬件 AES/SHA 指令）：
 *  - PBKDF2-HMAC-SHA1（1000 轮）用 [Mac] 手写循环，替代 Zip4j 纯 Java PBKDF2；
 *  - AES/CTR/NoPadding 交由 [Cipher] 整块吞吐，与 WinZip 的"ECB 加密计数器 + XOR"逐位等价；
 *  - HMAC-SHA1-80 在密文流末尾整段校验，替代逐 16 字节 mac.update。
 *
 * Zip4j 仅保留头解析职责（中央目录 FileHeader / AES extra field），容器规范细节：
 *  - 条目布局 `Salt | 2B password verifier | 密文 | 10B HMAC`；
 *  - 真实压缩方法存于 AES extra field（0x9901），方法号 99 仅为占位；
 *  - 每 16 字节 CTR 计数器块从 1 起按大端递增，与 JCE CTR 的 IV 语义一致。
 *
 * 密钥只驻留内存（LRU 缓存），符合「密码不落盘」硬约束。
 */
class ZipAesJceStreamFactory {

    /**
     * Opens a decrypted (and, for DEFLATE entries, inflated) stream for an AES-encrypted entry.
     *
     * Returns null when the entry is not AES-encrypted (caller should fall back to Zip4j).
     * Throws [ZipException] with type [ZipException.Type.WRONG_PASSWORD] when the password
     * verifier mismatches; [IOException] when the trailing HMAC check fails.
     */
    fun open(file: File, header: FileHeader, password: CharArray): InputStream? {
        if (password == null || header.encryptionMethod != EncryptionMethod.AES) return null
        val aesRecord = header.aesExtraDataRecord ?: return null
        val strength = aesRecord.aesKeyStrength ?: return null
        val saltLength = strength.saltLength
        val dataLength = header.compressedSize - saltLength - VERIFIER_SIZE - HMAC_SIZE
        if (dataLength < 0) return null

        val raf = RandomAccessFile(file, "r")
        try {
            val dataStart = locateDataStart(raf, header)
            raf.seek(dataStart)
            val salt = ByteArray(saltLength)
            raf.readFully(salt)
            val storedVerifier = ByteArray(VERIFIER_SIZE)
            raf.readFully(storedVerifier)

            val derived = ZipAesKeyCache.getOrDerive(password, salt, strength)
            if (derived[derived.size - 2] != storedVerifier[0] || derived[derived.size - 1] != storedVerifier[1]) {
                throw ZipException("Wrong password (AES password verifier mismatch)", ZipException.Type.WRONG_PASSWORD)
            }

            val keyLength = strength.keyLength
            // WinZip AES 的 CTR 计数器为块首小端（7-Zip Aes.c / Gladman / Zip4j 同款），
            // 与 JCE 内置 CTR 的大端计数不同，故用 ECB 加密计数块 + 手工 XOR。
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived.copyOf(keyLength), "AES"))
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(derived.copyOfRange(keyLength, keyLength + strength.macLength), "HmacSHA1"))

            val cipherTextStart = dataStart + saltLength + VERIFIER_SIZE
            val raw = AesCtrMacStream(raf, cipherTextStart, dataLength, mac, cipher)
            return when (aesRecord.compressionMethod) {
                CompressionMethod.DEFLATE -> InflaterInputStream(raw, Inflater(true), INFLATER_BUFFER_SIZE)
                CompressionMethod.STORE -> raw
                else -> {
                    runCatching { raw.close() }
                    throw ZipException(
                        "Unsupported AES real compression method: ${aesRecord.compressionMethod}",
                        ZipException.Type.UNKNOWN
                    )
                }
            }
        } catch (t: Throwable) {
            runCatching { raf.close() }
            throw t
        }
    }

    private fun locateDataStart(raf: RandomAccessFile, header: FileHeader): Long {
        raf.seek(header.offsetLocalHeader)
        val fixed = ByteArray(LOCAL_HEADER_SIZE)
        raf.readFully(fixed)
        if (readLeInt(fixed, 0) != LOCAL_HEADER_SIGNATURE) {
            throw ZipException("Invalid local header signature for AES entry", ZipException.Type.UNKNOWN)
        }
        val nameLength = readLeShort(fixed, 26)
        val extraLength = readLeShort(fixed, 28)
        return header.offsetLocalHeader + LOCAL_HEADER_SIZE + nameLength + extraLength
    }

    /**
     * Decrypts the ciphertext region [position, position + cipherTextLength) of a dedicated
     * [RandomAccessFile] and verifies the trailing HMAC-SHA1-80 once all ciphertext is consumed.
     *
     * Keystream: WinZip AES counts blocks as a little-endian integer starting at 1 in the FIRST
     * bytes of the 16-byte block (7-Zip Aes.c `++p[0]` / Gladman / Zip4j convention), not the
     * NIST big-endian CTR the JCE implements. Counter blocks are therefore batched 16384 at a
     * time and ECB-encrypted with a single [Cipher.doFinal] per 256KB chunk; plaintext is
     * XORed in place, so JNI crossings stay O(chunks) instead of O(blocks).
     */
    private class AesCtrMacStream(
        private val raf: RandomAccessFile,
        position: Long,
        private val cipherTextLength: Long,
        private val mac: Mac,
        private val cipher: Cipher
    ) : InputStream() {

        private val cipherBuffer = ByteArray(DECRYPT_CHUNK_SIZE)
        private val plainBuffer = ByteArray(DECRYPT_CHUNK_SIZE)
        private val counterBuffer = ByteArray(DECRYPT_CHUNK_SIZE + 16)
        private val keystream = ByteArray(DECRYPT_CHUNK_SIZE + 16)
        private var nextCounter = 1L
        private var keystreamOffset = 0
        private var keystreamLength = 0
        private var plainStart = 0
        private var plainEnd = 0
        private var consumed = 0L
        private var verified = false

        init {
            Arrays.fill(counterBuffer, 8, counterBuffer.size, 0.toByte())
            raf.seek(position)
        }

        override fun read(): Int {
            if (plainStart >= plainEnd && !fillPlain()) return -1
            return plainBuffer[plainStart++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (plainStart >= plainEnd && !fillPlain()) return -1
            val count = minOf(len, plainEnd - plainStart)
            System.arraycopy(plainBuffer, plainStart, b, off, count)
            plainStart += count
            return count
        }

        private fun fillPlain(): Boolean {
            if (consumed >= cipherTextLength) {
                verifyAtEnd()
                return false
            }
            val toRead = minOf(DECRYPT_CHUNK_SIZE.toLong(), cipherTextLength - consumed).toInt()
            val read = readFully(cipherBuffer, toRead)
            if (read <= 0) throw IOException("Truncated AES entry ciphertext")
            consumed += read
            mac.update(cipherBuffer, 0, read)
            ensureKeystream(read)
            for (i in 0 until read) {
                plainBuffer[i] = (cipherBuffer[i].toInt() xor keystream[keystreamOffset + i].toInt()).toByte()
            }
            keystreamOffset += read
            plainStart = 0
            plainEnd = read
            if (consumed >= cipherTextLength) verifyAtEnd()
            return true
        }

        /** ECB-encryptes sequential counter blocks so at least [length] keystream bytes are ready. */
        private fun ensureKeystream(length: Int) {
            var available = keystreamLength - keystreamOffset
            if (available >= length) return
            if (keystreamOffset > 0) {
                System.arraycopy(keystream, keystreamOffset, keystream, 0, available)
            }
            val blocks = ((length - available) + 15) / 16
            var counter = nextCounter
            var off = available
            repeat(blocks) {
                counterBuffer[off] = counter.toByte()
                counterBuffer[off + 1] = (counter ushr 8).toByte()
                counterBuffer[off + 2] = (counter ushr 16).toByte()
                counterBuffer[off + 3] = (counter ushr 24).toByte()
                counterBuffer[off + 4] = (counter ushr 32).toByte()
                counterBuffer[off + 5] = (counter ushr 40).toByte()
                counterBuffer[off + 6] = (counter ushr 48).toByte()
                counterBuffer[off + 7] = (counter ushr 56).toByte()
                off += 16
                counter++
            }
            nextCounter = counter
            cipher.doFinal(counterBuffer, available, blocks * 16, keystream, available)
            keystreamOffset = 0
            keystreamLength = available + blocks * 16
        }

        private fun readFully(buffer: ByteArray, length: Int): Int {
            var offset = 0
            while (offset < length) {
                val n = raf.read(buffer, offset, length - offset)
                if (n < 0) break
                offset += n
            }
            return offset
        }

        private fun verifyAtEnd() {
            if (verified) return
            verified = true
            val stored = ByteArray(HMAC_SIZE)
            var offset = 0
            while (offset < HMAC_SIZE) {
                val n = raf.read(stored, offset, HMAC_SIZE - offset)
                if (n < 0) throw IOException("Truncated AES entry HMAC")
                offset += n
            }
            val computed = mac.doFinal()
            for (i in 0 until HMAC_SIZE) {
                if (computed[i] != stored[i]) {
                    throw IOException("AES HMAC verification failed: archive data corrupted")
                }
            }
        }

        override fun close() {
            runCatching { raf.close() }
        }
    }

    companion object {
        private const val VERIFIER_SIZE = 2
        private const val HMAC_SIZE = 10
        private const val LOCAL_HEADER_SIZE = 30
        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
        private const val DECRYPT_CHUNK_SIZE = 256 * 1024
        private const val INFLATER_BUFFER_SIZE = 64 * 1024

        private fun readLeShort(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun readLeInt(b: ByteArray, off: Int): Int =
            readLeShort(b, off) or (readLeShort(b, off + 2) shl 16)
    }
}

/**
 * 进程级内存 LRU，缓存 WinZip AES 派生密钥（AES key + HMAC key + password verifier），
 * 结构与 [SevenZKeyCache] 一致：密钥仅驻留内存，密码清除时全量清零，严禁落盘。
 */
object ZipAesKeyCache {

    private const val PBKDF2_ITERATIONS = 1000
    private const val VERIFIER_SIZE = 2
    private const val MAX_CACHE_ENTRIES = 256

    private class CacheKey(val password: String, val keyLength: Int, val salt: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is CacheKey &&
                    other.password == password &&
                    other.keyLength == keyLength &&
                    other.salt.contentEquals(salt)

        override fun hashCode(): Int =
            31 * (31 * password.hashCode() + keyLength) + Arrays.hashCode(salt)
    }

    private val lock = ReentrantLock()
    private val map = object : LinkedHashMap<CacheKey, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, ByteArray>?): Boolean {
            if (size > MAX_CACHE_ENTRIES && eldest != null) {
                Arrays.fill(eldest.value, 0.toByte())
                return true
            }
            return false
        }
    }

    val size: Int
        get() = lock.withLock { map.size }

    fun getOrDerive(password: CharArray, salt: ByteArray, strength: AesKeyStrength): ByteArray {
        // 与 ArchiveHandlePool.EncryptedHandleKey 相同的密级：密码仅驻留内存字符串。
        val cacheKey = CacheKey(String(password), strength.keyLength, salt)
        lock.withLock { map[cacheKey]?.let { return it.clone() } }

        val derived = derivePbkdf2Sha1(
            password, salt, PBKDF2_ITERATIONS,
            strength.keyLength + strength.macLength + VERIFIER_SIZE
        )
        lock.withLock { map[cacheKey] = derived.clone() }
        return derived
    }

    fun clear() {
        lock.withLock {
            for (value in map.values) Arrays.fill(value, 0.toByte())
            map.clear()
        }
    }

    /**
     * Standard PBKDF2-HMAC-SHA1 (RFC 2898). WinZip AES output length (≤ 50 bytes) stays within
     * a single SHA-1 block, so one iteration index per call suffices; Mac.getInstance resolves
     * to Conscrypt on Android (hardware-backed HMAC) and the JCE default provider on JVM.
     */
    private fun derivePbkdf2Sha1(password: CharArray, salt: ByteArray, iterations: Int, dkLength: Int): ByteArray {
        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(passwordBytes, "HmacSHA1"))
            val u = ByteArray(mac.macLength)
            val output = ByteArray(dkLength)
            var offset = 0
            var blockIndex = 1
            while (offset < dkLength) {
                mac.update(salt)
                mac.update(byteArrayOf(0, 0, (blockIndex ushr 8).toByte(), blockIndex.toByte()))
                mac.doFinal(u, 0)
                val t = u.copyOf()
                for (i in 2..iterations) {
                    mac.update(u)
                    mac.doFinal(u, 0)
                    for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
                val copyLength = minOf(t.size, dkLength - offset)
                System.arraycopy(t, 0, output, offset, copyLength)
                offset += copyLength
                blockIndex++
            }
            return output
        } finally {
            Arrays.fill(passwordBytes, 0.toByte())
        }
    }
}
