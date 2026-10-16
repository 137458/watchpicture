package com.watchpicture.app.archive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 缓存键十六进制编码：查表实现对既有 `"%02x".format(it)` 的逐字节输出必须完全一致，
 * 否则既有磁盘/内存缓存键会整体失效（缓存被穿透后重新生成）。
 */
class HexEncodingTest {

    private fun referenceHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `toLowerHex 与逐字节 String_format 输出完全一致`() {
        val samples = listOf(
            byteArrayOf(),
            byteArrayOf(0x00),
            byteArrayOf(0xFF.toByte(), 0x7F, 0x80.toByte()),
            byteArrayOf(0x00, 0x0F.toByte(), 0xF0.toByte(), 0x01),
            ByteArray(64) { (it * 7 - 100).toByte() }
        )
        for (bytes in samples) {
            assertEquals(referenceHex(bytes), bytes.toLowerHex())
        }
    }

    @Test
    fun `toLowerHex 输出为小写且每位字节固定两位`() {
        assertEquals("abcd", byteArrayOf(0xAB.toByte(), 0xCD.toByte()).toLowerHex())
        assertEquals("0001ff", byteArrayOf(0x00, 0x01, 0xFF.toByte()).toLowerHex())
    }
}
