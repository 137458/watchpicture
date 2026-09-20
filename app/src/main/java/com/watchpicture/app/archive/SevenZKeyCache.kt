package com.watchpicture.app.archive

import java.security.MessageDigest
import java.util.Arrays
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Global LRU cache for 7z AES-256 PBKDF2 derived keys.
 *
 * The 7z specification mandates up to 2^19 (524,288) SHA-256 iterations for key derivation,
 * costing 500ms~1500ms of pure CPU load per invocation in JVM / Android ART.
 *
 * In a typical multi-folder or sequential 7z archive session, the salt and iteration
 * power remain identical across all streams. This cache completely eliminates repeated
 * KDF computations, dropping subsequent lookups from ~1000ms to < 0.01ms.
 */
object SevenZKeyCache {

    private const val MAX_CACHE_SIZE = 256

    data class Stats(
        val hitCount: Long,
        val missCount: Long
    )

    private val lock = ReentrantLock()
    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)

    val stats: Stats
        get() = Stats(hits.get(), misses.get())

    private data class CacheKey(
        val pwdHash: Int,
        val saltHash: Long,
        val numCyclesPower: Int
    )

    private val lruMap = object : LinkedHashMap<CacheKey, ByteArray>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, ByteArray>?): Boolean {
            if (size > MAX_CACHE_SIZE && eldest != null) {
                // Zeroize evicted key bytes for memory safety
                Arrays.fill(eldest.value, 0.toByte())
                return true
            }
            return false
        }
    }

    val size: Int
        get() = lock.withLock { lruMap.size }

    /**
     * Retrieves an already derived 32-byte AES key or computes it via SHA-256 iterations.
     */
    fun getOrDerive(
        passwordBytes: ByteArray,
        salt: ByteArray,
        numCyclesPower: Int
    ): ByteArray {
        val pwdHash = Arrays.hashCode(passwordBytes)
        val saltHash = computeSaltHash(salt)
        val key = CacheKey(pwdHash, saltHash, numCyclesPower)

        lock.withLock {
            val existing = lruMap[key]
            if (existing != null) {
                hits.incrementAndGet()
                return existing.clone()
            }
        }

        misses.incrementAndGet()

        // Derive key using standard 7z PBKDF2
        val derivedKey = if (numCyclesPower == 63) {
            val k = ByteArray(32)
            System.arraycopy(salt, 0, k, 0, salt.size.coerceAtMost(32))
            val pwdLen = passwordBytes.size.coerceAtMost(32 - salt.size.coerceAtMost(32))
            if (pwdLen > 0) {
                System.arraycopy(passwordBytes, 0, k, salt.size.coerceAtMost(32), pwdLen)
            }
            k
        } else {
            val nativeKey = if (Native7z.isAvailable) {
                runCatching {
                    Native7z.nativeDeriveKey(passwordBytes, salt, numCyclesPower)
                }.getOrNull()
            } else null
            nativeKey ?: sha256Password(passwordBytes, numCyclesPower, salt)
        }

        lock.withLock {
            lruMap[key] = derivedKey.clone()
        }

        return derivedKey
    }

    /**
     * Standard 7z PBKDF2 implementation using SHA-256 with an 8-byte counter.
     */
    fun sha256Password(password: ByteArray, numCyclesPower: Int, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val counter = ByteArray(8)
        val cycles = 1L shl numCyclesPower
        for (i in 0L until cycles) {
            digest.update(salt)
            digest.update(password)
            digest.update(counter)
            for (j in counter.indices) {
                counter[j] = (counter[j] + 1).toByte()
                if (counter[j].toInt() != 0) {
                    break
                }
            }
        }
        return digest.digest()
    }

    /**
     * Safely clears all cached keys and zeroes out memory.
     */
    fun clear() {
        lock.withLock {
            for (keyBytes in lruMap.values) {
                Arrays.fill(keyBytes, 0.toByte())
            }
            lruMap.clear()
        }
    }

    private fun computeSaltHash(salt: ByteArray): Long {
        var h = 1125899906842597L // FNV offset basis
        for (b in salt) {
            h = (h xor (b.toLong() and 0xFFL)) * 1099511628211L
        }
        return h
    }
}
