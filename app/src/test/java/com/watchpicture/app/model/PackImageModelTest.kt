package com.watchpicture.app.model

import com.watchpicture.app.coil.ZipImageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackImageModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `resolves direct image file model when directFilePath points to image`() {
        val imageFile = tempFolder.newFile("sample.jpg")
        val image = PackImage(
            packId = "pack1",
            entryPath = "sample.jpg",
            displayName = "sample.jpg",
            directFilePath = imageFile.absolutePath
        )

        val model = image.toImageModel()
        assertTrue(model is File)
        assertEquals(imageFile.absolutePath, (model as File).absolutePath)
    }

    @Test
    fun `resolves directory model when directFilePath points to folder`() {
        val folder = tempFolder.newFolder("album")
        val image = PackImage(
            packId = "pack1",
            entryPath = "sample.png",
            displayName = "sample.png",
            directFilePath = folder.absolutePath
        )

        val model = image.toImageModel()
        assertTrue(model is File)
        assertEquals(File(folder, "sample.png").absolutePath, (model as File).absolutePath)
    }

    @Test
    fun `resolves zip entry model when directFilePath points to zip archive`() {
        val zipFile = tempFolder.newFile("archive.zip")
        val image = PackImage(
            packId = "pack1",
            entryPath = "photos/01.jpg",
            displayName = "01.jpg",
            directFilePath = zipFile.absolutePath
        )

        val model = image.toImageModel(sessionPassword = "secret")
        assertTrue(model is ZipImageSource)
        val zipSource = model as ZipImageSource
        assertEquals(zipFile.absolutePath, zipSource.zipFile.absolutePath)
        assertEquals("photos/01.jpg", zipSource.entryName)
        assertEquals("secret", zipSource.password)
    }

    @Test
    fun `resolves zip entry model falling back to passwordStore when sessionPassword is null`() {
        val zipFile = tempFolder.newFile("archive_locked.zip")
        val image = PackImage(
            packId = "pack_locked",
            entryPath = "01.png",
            displayName = "01.png",
            directFilePath = zipFile.absolutePath
        )

        val store = com.watchpicture.app.security.SessionPasswordStore()
        store.set("pack_locked", "unlockedPass")

        val model = image.toImageModel(sessionPassword = null, passwordStore = store)
        assertTrue(model is ZipImageSource)
        val zipSource = model as ZipImageSource
        assertEquals("unlockedPass", zipSource.password)
    }
}
