package com.watchpicture.app.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Header 加密 7z 的登记状态机：解锁前（无密码）无法枚举条目是 7z 规范行为，
 * 每次列表刷新重复"native 失败 ×2 + Commons 尝试"是纯浪费；登记后无密码调用
 * 快速失败，带密码调用不受影响。
 */
class HeaderEncryptedRegistryTest {

    private val manager = SevenZArchiveManager()

    @get:Rule
    val tempFolder = org.junit.rules.TemporaryFolder()

    private val fakeHeaderEncrypted = File("/nonexistent/header_encrypted.7z")
    private val fakePlain = File("/nonexistent/plain.7z")

    @Before
    fun resetRegistry() {
        SevenZArchiveManager.forgetHeaderEncryptedForTest(fakeHeaderEncrypted)
        SevenZArchiveManager.forgetHeaderEncryptedForTest(fakePlain)
    }

    @Test
    fun `未登记时 fast-fail 不生效`() {
        assertFalse(SevenZArchiveManager.isHeaderEncryptedRemembered(fakeHeaderEncrypted))
    }

    @Test
    fun `登记后 isHeaderEncryptedRemembered 为真且支持解除`() {
        SevenZArchiveManager.rememberHeaderEncrypted(fakeHeaderEncrypted)
        assertTrue(SevenZArchiveManager.isHeaderEncryptedRemembered(fakeHeaderEncrypted))

        SevenZArchiveManager.forgetHeaderEncryptedForTest(fakeHeaderEncrypted)
        assertFalse(SevenZArchiveManager.isHeaderEncryptedRemembered(fakeHeaderEncrypted))
    }

    @Test
    fun `登记后 isEncrypted 直接为真且不触碰文件`() {
        SevenZArchiveManager.rememberHeaderEncrypted(fakeHeaderEncrypted)
        // 文件不存在：若触碰文件，native/Commons 判定不可能返回 true
        assertTrue(manager.isEncrypted(fakeHeaderEncrypted))
    }

    @Test
    fun `登记后无密码枚举快速返回空且不触碰文件`() {
        SevenZArchiveManager.rememberHeaderEncrypted(fakeHeaderEncrypted)
        // 文件不存在：触碰文件会抛 FileNotFoundException 或返回空，fast-fail 同样返回空；
        // 用 isEncrypted 同款不触碰语义保证幂等
        assertEquals(emptyList<ArchiveEntryInfo>(), manager.getMediaEntries(fakeHeaderEncrypted, null))
    }

    @Test
    fun `登记不影响带密码调用与未登记文件`() {
        SevenZArchiveManager.rememberHeaderEncrypted(fakeHeaderEncrypted)
        assertFalse(SevenZArchiveManager.isHeaderEncryptedRemembered(fakePlain))
        assertFalse(manager.isEncrypted(fakePlain))
    }

    @Test
    fun `同路径替换归档后登记必须失效`() {
        val realFile = tempFolder.newFile("header_encrypted_real.7z").apply { writeBytes(ByteArray(8)) }
        try {
            SevenZArchiveManager.rememberHeaderEncrypted(realFile)
            assertTrue(SevenZArchiveManager.isHeaderEncryptedRemembered(realFile))

            // 同路径被替换(mtime/size 变化):旧登记不得再把新文件误判为加密
            realFile.writeBytes(ByteArray(32))
            realFile.setLastModified(realFile.lastModified() + 60_000L)
            assertFalse(
                "replaced archive at same path must not stay flagged header-encrypted",
                SevenZArchiveManager.isHeaderEncryptedRemembered(realFile)
            )
        } finally {
            SevenZArchiveManager.forgetHeaderEncryptedForTest(realFile)
        }
    }
}
