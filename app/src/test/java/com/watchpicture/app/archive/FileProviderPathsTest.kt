package com.watchpicture.app.archive

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FileProvider 共享根契约：file_paths.xml 必须保留 external-path 根。
 *
 * 文件夹图包的磁盘直连媒体（VideoLauncher directEntryFile）位于用户任意存储目录，
 * 无法静态枚举；移除 external-path 会让 getUriForFile 对这类文件抛
 * "Failed to find configured root"，「用其它播放器打开」对文件夹图包整体失效。
 */
class FileProviderPathsTest {

    @Test
    fun `file_paths 必须保留 external-path 根以覆盖文件夹图包直连文件`() {
        val file = File("src/main/res/xml/file_paths.xml")
        assertTrue("file_paths.xml 必须存在", file.isFile)
        val xml = file.readText()
        assertTrue(
            "file_paths.xml 缺少 external-path：文件夹图包视频将无法共享给外部播放器",
            Regex("<external-path\\s+name=\"[^\"]+\"\\s+path=\"[^\"]*\"\\s*/>").containsMatchIn(xml)
        )
    }

    @Test
    fun `file_paths 保留播放副本与更新包所需的三类私有根`() {
        val xml = File("src/main/res/xml/file_paths.xml").readText()
        assertTrue("缺少 cache-path（视频播放副本 cacheDir/playback）", xml.contains("<cache-path"))
        assertTrue("缺少 external-cache-path（更新安装包 externalCacheDir）", xml.contains("<external-cache-path"))
        assertTrue("缺少 files-path（应用私有 filesDir）", xml.contains("<files-path"))
    }
}
