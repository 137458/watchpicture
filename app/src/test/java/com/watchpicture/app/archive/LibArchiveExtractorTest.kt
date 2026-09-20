package com.watchpicture.app.archive

import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibArchiveExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `isAvailable returns false safely on host JVM without throwing UnsatisfiedLinkError`() {
        val available = LibArchiveExtractor.isAvailable
        // On desktop JVM without Android .so in java.library.path, must safely return false
        assertFalse(available)
    }

    @Test
    fun `extractWithOpportunisticCache returns false safely on unsupported or invalid input`() {
        val dummyFile = tempFolder.newFile("dummy.7z")
        val success = LibArchiveExtractor.extractWithOpportunisticCache(
            file = dummyFile,
            targetEntryName = "image.jpg",
            password = null,
            maxLookahead = 2
        ) { _, _ -> }

        assertFalse("Should return false safely when native library is not available or file is invalid", success)
    }
}
