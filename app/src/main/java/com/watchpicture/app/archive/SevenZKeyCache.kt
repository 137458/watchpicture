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

    // 7-Zip caps numCyclesPower at 24 (kNumCyclesPowerMax). The 7z header field is
    // 6 bits wide, so a malicious archive can declare e.g. 62 -> 2^62 iterations,
    // hanging the CPU indefinitely (DoS). Clamp to the spec maximum.
    private const val MAX_CYCLES_POWER = 24

    data class Stats(
        val hitCount: Long,
        val missCount: Long
    )

    private val lock = ReentrantLock()
    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)

    val stats: Stats
        get() = Stats(hits.get(), misses.get())

    // Full password and salt bytes form the key: int/long hashes collide for different
    // inputs, which would silently return a derived key computed from the wrong password.
    private class CacheKey(
        val passwordBytes: ByteArray,
        val salt: ByteArray,
        val numCyclesPower: Int
    ) {
        override fun equals(other: Any?): Boolean =
            other is CacheKey &&
                numCyclesPower == other.numCyclesPower &&
                passwordBytes.contentEquals(other.passwordBytes) &&
                salt.contentEquals(other.salt)

        override fun hashCode(): Int {
            var result = passwordBytes.contentHashCode()
            result = 31 * result + salt.contentHashCode()
            result = 31 * result + numCyclesPower
            return result
        }
    }

    private val lruMap = object : LinkedHashMap<CacheKey, ByteArray>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, ByteArray>?): Boolean {
            if (size > MAX_CACHE_SIZE && eldest != null) {
                // Zeroize evicted derived key and key password bytes for memory safety
                Arrays.fill(eldest.value, 0.toByte())
                Arrays.fill(eldest.key.passwordBytes, 0.toByte())
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
        // Clamp at the entry so the native path and the CacheKey never see an absurd iteration
        // count. 63 is a special "no-iteration" sentinel (direct salt+password key), kept as-is;
        // anything above the spec max of 24 is clamped to defeat a malicious archive declaring
        // e.g. 62 -> ~2^62 PBKDF2 iterations = CPU DoS.
        val cyclesPower = if (numCyclesPower == 63) {
            63
        } else {
            numCyclesPower.coerceAtMost(MAX_CYCLES_POWER)
        }

        val key = CacheKey(passwordBytes.copyOf(), salt.copyOf(), cyclesPower)

        lock.withLock {
            val existing = lruMap[key]
            if (existing != null) {
                hits.incrementAndGet()
                return existing.clone()
            }
        }

        misses.incrementAndGet()

        // Derive key using standard 7z PBKDF2
        val derivedKey = if (cyclesPower == 63) {
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
                    Native7z.nativeDeriveKey(passwordBytes, salt, cyclesPower)
                }.getOrNull()
            } else null
            nativeKey ?: sha256Password(passwordBytes, cyclesPower, salt)
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
        // Guard against absurd iteration counts declared by malicious archives.
        val effectiveCyclesPower = numCyclesPower.coerceIn(0, MAX_CYCLES_POWER)
        val cycles = 1L shl effectiveCyclesPower
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
            for (entry in lruMap.entries) {
                Arrays.fill(entry.value, 0.toByte())
                Arrays.fill(entry.key.passwordBytes, 0.toByte())
            }
            lruMap.clear()
        }
    }

}
