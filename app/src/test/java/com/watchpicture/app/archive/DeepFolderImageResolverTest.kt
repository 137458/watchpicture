package com.watchpicture.app.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeepFolderImageResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `collects images recursively from nested subdirectories in natural order`() {
        val rootDir = tempFolder.newFolder("Album")

        val sub1 = File(rootDir, "ch01").apply { mkdirs() }
        val sub2 = File(rootDir, "ch02").apply { mkdirs() }

        val f1 = File(sub1, "10.png").apply { writeBytes(byteArrayOf(1)) }
        val f2 = File(sub1, "2.png").apply { writeBytes(byteArrayOf(1)) }
        val f3 = File(sub2, "01.jpg").apply { writeBytes(byteArrayOf(1)) }
        val txt = File(sub1, "info.txt").apply { writeText("hello") }
        val dsStore = File(sub2, ".DS_Store").apply { writeBytes(byteArrayOf(0)) }

        val images = DeepFolderImageResolver.collectImages(rootDir)

        assertEquals(3, images.size)
        // Check relative paths or filenames
        val relativePaths = images.map { it.relativeTo(rootDir).path.replace('\\', '/') }
        assertEquals(listOf("ch01/2.png", "ch01/10.png", "ch02/01.jpg"), relativePaths)
    }

    @Test
    fun `returns empty list if directory has no images`() {
        val emptyDir = tempFolder.newFolder("Empty")
        val txt = File(emptyDir, "readme.txt").apply { writeText("nothing") }

        val images = DeepFolderImageResolver.collectImages(emptyDir)
        assertTrue(images.isEmpty())
    }
}
