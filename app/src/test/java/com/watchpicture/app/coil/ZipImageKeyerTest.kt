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

        // Currently, keyer does not include password in key, so these will fail (RED)
        assertNotEquals("Key with password must differ from key without password", keyNoPass, keyPass1)
        assertNotEquals("Key with different passwords must differ", keyPass1, keyPass2)
    }
}
