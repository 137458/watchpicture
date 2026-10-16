package com.watchpicture.app.archive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ZIP 扫描取数路由：仅 STORED 且非目录条目走自持 native 会话的 mmap 零拷贝直通；
 * DEFLATE 保持池化 java.util.zip 流（引擎把解压堆内存持有到 close，整包直通会无界增长）。
 */
class ZipSweepEntryRouterTest {

    @Test
    fun `会话为空（引擎不可用或加密包）时全部走池化流`() {
        assertEquals(
            ZipSweepRoute.PooledStream,
            ZipSweepEntryRouter.route(session = null, entryName = "img/a.jpg")
        )
    }

    @Test
    fun `STORED 条目路由到原生零拷贝直通并携带条目索引`() {
        val stored = NativeZipEntry(index = 7, path = "img/a.jpg", size = 100L, compressedSize = 100L, isDirectory = false)
        assertEquals(ZipSweepRoute.NativeDirectBuffer(7), ZipSweepEntryRouter.route(stored))
    }

    @Test
    fun `DEFLATE 条目保持池化流以免引擎堆无界增长`() {
        val deflated = NativeZipEntry(index = 2, path = "img/b.jpg", size = 1000L, compressedSize = 800L, isDirectory = false)
        assertEquals(ZipSweepRoute.PooledStream, ZipSweepEntryRouter.route(deflated))
    }

    @Test
    fun `零长度条目与目录条目不路由直通`() {
        val empty = NativeZipEntry(index = 3, path = "empty.jpg", size = 0L, compressedSize = 0L, isDirectory = false)
        assertEquals(ZipSweepRoute.PooledStream, ZipSweepEntryRouter.route(empty))
        val dir = NativeZipEntry(index = 4, path = "dir/", size = 100L, compressedSize = 100L, isDirectory = true)
        assertEquals(ZipSweepRoute.PooledStream, ZipSweepEntryRouter.route(dir))
    }
}
