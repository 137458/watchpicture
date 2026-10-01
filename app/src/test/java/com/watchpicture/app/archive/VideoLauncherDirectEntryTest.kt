package com.watchpicture.app.archive

import com.watchpicture.app.model.PackImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * VideoLauncher.directEntryFile 的来源解析行为:
 * - 目录型 directFilePath(目录 + entryPath 相对路径)照旧拼接解析;
 * - 文件夹图包每条媒体各自持有 directFilePath=媒体文件本身,视频文件必须
 *   直接命中,否则会落空并被归档分支误当压缩包打开(实测全失败);
 * - 压缩包场景 directFilePath=归档文件,绝不能被当作视频直接返回。
 */
class VideoLauncherDirectEntryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `directory directFilePath joins entryPath as before`() {
        val dir = tempFolder.newFolder("folder_pack")
        val video = File(dir, "clip.mp4").apply { writeBytes(ByteArray(16)) }

        val item = PackImage(
            packId = dir.absolutePath,
            entryPath = "clip.mp4",
            displayName = "clip.mp4",
            directFilePath = dir.absolutePath
        )

        assertEquals(video, VideoLauncher.directEntryFile(item))
    }

    @Test
    fun `per-file directFilePath video file resolves directly`() {
        val video = tempFolder.newFile("episode_01.mkv").apply { writeBytes(ByteArray(16)) }

        val item = PackImage(
            packId = "pack-1",
            entryPath = "episode_01.mkv",
            displayName = "episode_01.mkv",
            directFilePath = video.absolutePath
        )

        assertEquals("video file itself must be playable directly", video, VideoLauncher.directEntryFile(item))
    }

    @Test
    fun `archive file as directFilePath must not be treated as playable video`() {
        val archive = tempFolder.newFile("pack.zip").apply { writeBytes(ByteArray(16)) }

        val item = PackImage(
            packId = "pack-1",
            entryPath = "inside_video.mp4",
            displayName = "inside_video.mp4",
            directFilePath = archive.absolutePath
        )

        assertNull("archive file must fall through to archive materialization", VideoLauncher.directEntryFile(item))
    }

    @Test
    fun `missing or empty file returns null`() {
        val missing = File(tempFolder.root, "ghost.mp4")
        val itemMissing = PackImage(
            packId = "pack-1",
            entryPath = "ghost.mp4",
            displayName = "ghost.mp4",
            directFilePath = missing.absolutePath
        )
        assertNull(VideoLauncher.directEntryFile(itemMissing))

        val empty = tempFolder.newFile("empty.mp4")
        val itemEmpty = PackImage(
            packId = "pack-1",
            entryPath = "empty.mp4",
            displayName = "empty.mp4",
            directFilePath = empty.absolutePath
        )
        assertNull(VideoLauncher.directEntryFile(itemEmpty))
    }
}
