package com.watchpicture.app.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class DeepFolderImageResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun nestedDir(root: File, depth: Int): File {
        var dir = root
        repeat(depth) { dir = File(dir, "a").apply { mkdirs() } }
        return dir
    }

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

        val images = DeepFolderImageResolver.collectMedia(rootDir)

        assertEquals(3, images.size)
        // Check relative paths or filenames
        val relativePaths = images.map { it.relativeTo(rootDir).path.replace('\\', '/') }
        assertEquals(listOf("ch01/2.png", "ch01/10.png", "ch02/01.jpg"), relativePaths)
    }

    @Test
    fun `returns empty list if directory has no images`() {
        val emptyDir = tempFolder.newFolder("Empty")
        val txt = File(emptyDir, "readme.txt").apply { writeText("nothing") }

        val images = DeepFolderImageResolver.collectMedia(emptyDir)
        assertTrue(images.isEmpty())
    }

    @Test
    fun `normal deep nesting within cap still collects media`() {
        val root = tempFolder.newFolder("deep_ok")
        val deep = nestedDir(root, 30)
        File(deep, "page_001.jpg").writeBytes(ByteArray(4))

        val collected = DeepFolderImageResolver.collectMedia(root)
        assertEquals(1, collected.size)
    }

    @Test
    fun `traversal depth is capped against pathological trees`() {
        val root = tempFolder.newFolder("deep_cap")
        val beyond = nestedDir(root, 55)
        File(beyond, "beyond.jpg").writeBytes(ByteArray(4))
        // 正常范围内的兄弟子树必须仍然收集
        val normal = File(root, "normal").apply { mkdirs() }
        File(normal, "ok.jpg").writeBytes(ByteArray(4))

        val collected = DeepFolderImageResolver.collectMedia(root)
        assertEquals(
            "beyond-cap depth must be pruned, normal subtrees still collected",
            listOf("ok.jpg"),
            collected.map { it.name }
        )
    }

    @Test
    fun `directory symlink cycle terminates`() {
        val root = tempFolder.newFolder("cycle_root")
        val inner = File(root, "inner").apply { mkdirs() }
        File(inner, "pic.jpg").writeBytes(ByteArray(4))
        val link = File(root, "link")
        val created = try {
            Files.createSymbolicLink(link.toPath(), root.toPath())
            true
        } catch (_: Exception) {
            false
        }
        assumeTrue("host cannot create symlinks; cycle test skipped", created)

        val collected = DeepFolderImageResolver.collectMedia(root)
        assertEquals(1, collected.size)
        assertTrue(collected.single().name == "pic.jpg")
    }
}
