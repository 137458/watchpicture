package com.watchpicture.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {

    @Test
    fun testVersionComparison_standardSemanticVersions() {
        assertTrue(UpdateManager.compareVersions("v1.1.0", "v1.0.9") > 0)
        assertTrue(UpdateManager.compareVersions("1.0.9", "1.1.0") < 0)
        assertEquals(0, UpdateManager.compareVersions("v1.0.0", "1.0.0"))
        assertEquals(0, UpdateManager.compareVersions("1.0.0", "1.0.0"))
    }

    @Test
    fun testVersionComparison_multiSegmentAndPrefixes() {
        assertTrue(UpdateManager.compareVersions("v1.0.1", "v1.0.0") > 0)
        assertTrue(UpdateManager.compareVersions("v1.10.0", "v1.2.0") > 0)
        assertTrue(UpdateManager.compareVersions("v2.0.0", "v1.99.99") > 0)
        assertTrue(UpdateManager.compareVersions("1.0.0.1", "1.0.0") > 0)
        assertEquals(0, UpdateManager.compareVersions("v1.0", "1.0.0"))
    }

    @Test
    fun testHasUpdateLogic_withIgnoredVersion() {
        val currentVersion = "v1.0.0"
        val latestVersion = "v1.1.0"
        val ignoredVersion = "v1.1.0"

        val hasUpdate = UpdateManager.compareVersions(latestVersion, currentVersion) > 0
        assertTrue("Should detect newer version", hasUpdate)

        val shouldPrompt = hasUpdate && latestVersion != ignoredVersion
        assertFalse("Should not prompt if version is ignored", shouldPrompt)
    }

    @Test
    fun testAssetMatchingFiltersApk() {
        val assets = listOf(
            GithubAsset(name = "output-metadata.json", browserDownloadUrl = "https://example.com/meta.json", size = 100),
            GithubAsset(name = "watchpicture-v1.1.0.apk", browserDownloadUrl = "https://example.com/app.apk", size = 1024000),
            GithubAsset(name = "sources.tar.gz", browserDownloadUrl = "https://example.com/src.tar.gz", size = 5000)
        )

        val apkAsset = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        assertEquals("watchpicture-v1.1.0.apk", apkAsset?.name)
        assertEquals("https://example.com/app.apk", apkAsset?.browserDownloadUrl)
        assertEquals(1024000L, apkAsset?.size)
    }
}
