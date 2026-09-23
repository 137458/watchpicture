package com.watchpicture.app.coil

import coil3.request.Options
import okio.FileSystem
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class ZipImageKeyerTest {

    @Test
    fun `key differs when password changes`() {
        val keyer = ZipImageKeyer()
        val dummyFile = File("test.zip")
        val options = Options(
            context = object : android.content.ContextWrapper(null) {},
            fileSystem = FileSystem.SYSTEM
        )

        val sourceNoPass = ZipImageSource(dummyFile, "01.jpg", null)
        val sourcePass1 = ZipImageSource(dummyFile, "01.jpg", "password123")
        val sourcePass2 = ZipImageSource(dummyFile, "01.jpg", "otherPassword")

        val keyNoPass = keyer.key(sourceNoPass, options)
        val keyPass1 = keyer.key(sourcePass1, options)
        val keyPass2 = keyer.key(sourcePass2, options)

        assertNotEquals("Key with password must differ from key without password", keyNoPass, keyPass1)
        assertNotEquals("Key with different passwords must differ", keyPass1, keyPass2)
    }

    @Test
    fun `key includes lastModified and differentiates thumbnail configurations`() {
        val keyer = ZipImageKeyer()
        val dummyFile = File("test.zip")
        val options = Options(
            context = object : android.content.ContextWrapper(null) {},
            fileSystem = FileSystem.SYSTEM
        )

        val fullSource = ZipImageSource(dummyFile, "01.jpg", null, isThumbnail = false)
        val thumb360SourceA = ZipImageSource(dummyFile, "01.jpg", null, isThumbnail = true, targetSizePx = 360)
        val thumb360SourceB = ZipImageSource(dummyFile, "01.jpg", null, isThumbnail = true, targetSizePx = 360)
        val thumb720Source = ZipImageSource(dummyFile, "01.jpg", null, isThumbnail = true, targetSizePx = 720)

        val fullKey = keyer.key(fullSource, options)
        val thumb360KeyA = keyer.key(thumb360SourceA, options)
        val thumb360KeyB = keyer.key(thumb360SourceB, options)
        val thumb720Key = keyer.key(thumb720Source, options)

        // Verify #mod is present in key
        org.junit.Assert.assertTrue("Key must contain #mod=", fullKey.contains("#mod="))
        org.junit.Assert.assertTrue("Key must contain #mod=", thumb360KeyA.contains("#mod="))

        // Verify identical sources share the exact same memoryCacheKey
        org.junit.Assert.assertEquals("Identical 360px thumbnail sources must produce identical keys", thumb360KeyA, thumb360KeyB)

        // Verify differentiation between full and thumbnail
        assertNotEquals("Full key must differ from thumbnail key", fullKey, thumb360KeyA)

        // Verify differentiation between different thumbnail sizes
        assertNotEquals("360px thumbnail key must differ from 720px thumbnail key", thumb360KeyA, thumb720Key)
    }
}
