package com.watchpicture.app.archive

import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.CachedAES256SHA256Decoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CachedAES256SHA256DecoderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        SevenZKeyCache.clear()
        CachedAES256SHA256Decoder.install()
    }

    @Test
    fun `decrypts encrypted 7z archive and hits KDF cache on repeated folder reads`() {
        val testFile = tempFolder.newFile("encrypted.7z")
        val password = "MySecretPassword123"
        val content0 = "First entry content in encrypted 7z".toByteArray()
        val content1 = "Second entry content in encrypted 7z".toByteArray()

        // 1. Create an AES-256 encrypted 7z archive
        SevenZOutputFile(testFile, password.toCharArray()).use { out ->
            val f0 = tempFolder.newFile("f0").apply { writeBytes(content0) }
            val e0 = out.createArchiveEntry(f0, "entry0.txt").apply { size = content0.size.toLong() }
            out.putArchiveEntry(e0)
            out.write(content0)
            out.closeArchiveEntry()

            val f1 = tempFolder.newFile("f1").apply { writeBytes(content1) }
            val e1 = out.createArchiveEntry(f1, "entry1.txt").apply { size = content1.size.toLong() }
            out.putArchiveEntry(e1)
            out.write(content1)
            out.closeArchiveEntry()
        }

        // 2. First read: triggers KDF and caches key
        val statsBefore = SevenZKeyCache.stats
        SevenZFile.builder().setFile(testFile).setPassword(password).get().use { sz ->
            val e0 = sz.nextEntry
            assertEquals("entry0.txt", e0.name)
            val read0 = sz.getInputStream(e0).readBytes()
            assertArrayEquals(content0, read0)
        }

        val statsAfterFirst = SevenZKeyCache.stats
        assertEquals(statsBefore.missCount + 1, statsAfterFirst.missCount)

        // 3. Second read (e.g. reopening stream / new session): must hit KDF cache in 0ms!
        SevenZFile.builder().setFile(testFile).setPassword(password).get().use { sz ->
            val e0 = sz.nextEntry
            val read0 = sz.getInputStream(e0).readBytes()
            assertArrayEquals(content0, read0)

            val e1 = sz.nextEntry
            val read1 = sz.getInputStream(e1).readBytes()
            assertArrayEquals(content1, read1)
        }

        val statsAfterSecond = SevenZKeyCache.stats
        assertTrue(
            "Cache hits must increase on second read! Hit count: ${statsAfterSecond.hitCount}",
            statsAfterSecond.hitCount > statsAfterFirst.hitCount
        )
    }
}
