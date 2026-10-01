package com.watchpicture.app.coil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * ZipImageKeyer.keyOf 的密码口径行为锁定:Keyer 必须与 ZipImageFetcher 同口径
 * 回填会话密码,否则同一解密结果会占两个内存缓存槽(pwd=none 与 pwd=实际键)。
 */
class ZipImageKeyerSessionTest {

    private val keyer = ZipImageKeyer()
    private val dummyFile = File("/tmp/dummy.zip")

    @Test
    fun `session-resolved password produces the same key as explicit password`() {
        val viaSession = keyer.keyOf(ZipImageSource(dummyFile, "01.jpg", null), "password123")
        val explicit = keyer.keyOf(ZipImageSource(dummyFile, "01.jpg", "password123"), "password123")
        assertEquals("session fallback must unify the memory-cache key", explicit, viaSession)
    }

    @Test
    fun `no password anywhere still keys as none`() {
        val key = keyer.keyOf(ZipImageSource(dummyFile, "01.jpg", null), null)
        assertNotEquals(key, keyer.keyOf(ZipImageSource(dummyFile, "01.jpg", null), "password123"))
    }
}
