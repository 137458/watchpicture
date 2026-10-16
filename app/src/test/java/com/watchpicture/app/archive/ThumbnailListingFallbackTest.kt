package com.watchpicture.app.archive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

/**
 * 弹性回落（复用其它尺寸缩略图）走目录清单缓存，必须满足两条不变量：
 * 1. 非标准尺寸的既有缩略图仍能被回落复用；
 * 2. 清单过期或被删除的文件不得被返回。
 */
class ThumbnailListingFallbackTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun bytes(index: Int): ByteArray = ("listing-page-" + index).toByteArray()

    @Test
    fun `非标准尺寸缩略图仍可被弹性回落复用`() {
        val dir = tempFolder.newFolder("cache_exotic")
        val cache = ThumbnailDiskCache(dir, maxSizeBytes = 64L * 1024 * 1024)
        val zip = tempFolder.newFile("pack-exotic.zip")

        // 512 不在候选尺寸表里，只能靠目录清单扫描找到
        cache.getOrPut(zip, "img/page.jpg", 512, null) { ByteArrayInputStream(bytes(5)) }

        val fallback = cache.get(zip, "img/page.jpg", 999, null)
        assertNotNull("非标准尺寸应被弹性回落复用", fallback)
        assertArrayEquals(bytes(5), fallback!!.readBytes())
    }

    @Test
    fun `清单内的已删除文件不会被返回`() {
        val dir = tempFolder.newFolder("cache_stale")
        val cache = ThumbnailDiskCache(dir, maxSizeBytes = 64L * 1024 * 1024)
        val zip = tempFolder.newFile("pack-stale.zip")

        cache.getOrPut(zip, "img/page.jpg", 512, null) { ByteArrayInputStream(bytes(9)) }
        val stored = cache.get(zip, "img/page.jpg", 999, null)
        assertNotNull(stored)

        // 删除后清单（TTL 内）仍含该文件名，但回落必须复核 exists()
        check(stored!!.delete())
        assertNull("已删除文件不得被返回", cache.get(zip, "img/page.jpg", 998, null))
    }

    @Test
    fun `标准候选尺寸写入后可被另一目标尺寸命中`() {
        val dir = tempFolder.newFolder("cache_standard")
        val cache = ThumbnailDiskCache(dir, maxSizeBytes = 64L * 1024 * 1024)
        val zip = tempFolder.newFile("pack-standard.zip")

        cache.getOrPut(zip, "img/page.jpg", 720, null) { ByteArrayInputStream(bytes(2)) }

        val fallback = cache.get(zip, "img/page.jpg", 360, null)
        assertNotNull("720 缩略图应可服务 360 请求", fallback)
        assertArrayEquals(bytes(2), fallback!!.readBytes())
    }
}
