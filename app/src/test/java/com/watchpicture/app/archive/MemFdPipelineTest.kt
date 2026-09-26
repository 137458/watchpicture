package com.watchpicture.app.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D2 memfd 管线的 JVM 可测面：宿主无 memfd（API 门槛不可用），验证回退门与缓存键契约；
 * 实机上的 fd/路径语义由真机验证覆盖。
 */
class MemFdPipelineTest {

    @Test
    fun `宿主环境管线不可用`() {
        assertFalse(MemFdPipeline.isAvailable)
    }

    @Test
    fun `不可用时 getOrPut 返回 null 且不执行 producer`() {
        var invoked = false
        val handle = MemFdPipeline.getOrPut("k") {
            invoked = true
        }
        assertNull(handle)
        assertFalse("回退门应短路 producer，避免无谓解压", invoked)
    }

    @Test
    fun `closeAll 在不可用时安全无操作`() {
        MemFdPipeline.closeAll()
    }

    @Test
    fun `缓存键稳定且区分条目与密码`() {
        val zip = File("/tmp/fake.zip")
        val key = MemFdPipeline.cacheKey(zip, "img/001.jpg", null)
        assertEquals(key, MemFdPipeline.cacheKey(zip, "img/001.jpg", null))

        assertNotEquals(key, MemFdPipeline.cacheKey(zip, "img/002.jpg", null))
        assertNotEquals(key, MemFdPipeline.cacheKey(zip, "img/001.jpg", "pw1"))
        assertNotEquals(
            MemFdPipeline.cacheKey(zip, "img/001.jpg", "pw1"),
            MemFdPipeline.cacheKey(zip, "img/001.jpg", "pw2")
        )
        // 文件 mtime 变化应得到新键（失效旧内容）
        assertNotEquals(
            MemFdPipeline.cacheKey(zip, "img/001.jpg", null),
            MemFdPipeline.cacheKey(File("/tmp/fake-mtime-changed.zip"), "img/001.jpg", null)
        )
    }

    @Test
    fun `缓存键不含明文密码`() {
        val zip = File("/tmp/fake.zip")
        val key = MemFdPipeline.cacheKey(zip, "img/001.jpg", "secret-password-秘密")
        assertFalse("缓存键不得驻留明文密码", key.contains("secret-password"))
        assertFalse(key.contains("秘密"))
        assertTrue(key.isNotEmpty())
    }

    @Test
    fun `配额常量与设计一致`() {
        assertEquals(3, MemFdPipeline.MAX_ENTRIES)
        assertTrue(MemFdPipeline.MAX_TOTAL_BYTES > 0)
    }
}
