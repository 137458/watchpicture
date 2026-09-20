package com.watchpicture.app.archive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.zip.CRC32

class SevenZKeyCacheTest {

    @Before
    fun setUp() {
        SevenZKeyCache.clear()
    }

    @Test
    fun `caches derived key and eliminates repeated KDF computation`() {
        val password = "TestPassword@123".toByteArray(Charsets.UTF_16LE)
        val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val numCyclesPower = 10 // 2^10 = 1024 for quick test in unit tests

        // 1. Initial derivation (cache miss)
        val statsBefore = SevenZKeyCache.stats
        val key1 = SevenZKeyCache.getOrDerive(password, salt, numCyclesPower)
        assertNotNull(key1)
        assertEquals(32, key1.size)
        assertEquals(statsBefore.missCount + 1, SevenZKeyCache.stats.missCount)

        // 2. Secondary derivation with exact same parameters (cache hit)
        val startNano = System.nanoTime()
        val key2 = SevenZKeyCache.getOrDerive(password, salt, numCyclesPower)
        val durationNano = System.nanoTime() - startNano
        assertArrayEquals(key1, key2)
        assertEquals(statsBefore.hitCount + 1, SevenZKeyCache.stats.hitCount)
        // Hit must be instant (< 1 millisecond / 1,000,000 ns)
        assertTrue("Cache hit took $durationNano ns, expected < 1,000,000 ns", durationNano < 1_000_000L)
    }

    @Test
    fun `differentiates keys when salt or password differs`() {
        val pwd1 = "Pwd1".toByteArray(Charsets.UTF_16LE)
        val pwd2 = "Pwd2".toByteArray(Charsets.UTF_16LE)
        val salt1 = byteArrayOf(1, 2, 3)
        val salt2 = byteArrayOf(4, 5, 6)

        val key1 = SevenZKeyCache.getOrDerive(pwd1, salt1, 5)
        val key2 = SevenZKeyCache.getOrDerive(pwd2, salt1, 5)
        val key3 = SevenZKeyCache.getOrDerive(pwd1, salt2, 5)

        // All keys must be different
        var diff12 = false
        var diff13 = false
        for (i in 0 until 32) {
            if (key1[i] != key2[i]) diff12 = true
            if (key1[i] != key3[i]) diff13 = true
        }
        assertTrue("Keys for different passwords must differ", diff12)
        assertTrue("Keys for different salts must differ", diff13)
    }

    @Test
    fun `clear safely purges all cached keys`() {
        val pwd = "PurgeTest".toByteArray(Charsets.UTF_16LE)
        val salt = byteArrayOf(9, 9, 9)

        SevenZKeyCache.getOrDerive(pwd, salt, 4)
        assertTrue(SevenZKeyCache.size > 0)

        SevenZKeyCache.clear()
        assertEquals(0, SevenZKeyCache.size)
    }
}
