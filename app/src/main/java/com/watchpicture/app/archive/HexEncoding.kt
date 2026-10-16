package com.watchpicture.app.archive

/**
 * 缓存键哈希的十六进制编码。
 *
 * `joinToString("") { "%02x".format(it) }` 每个字节都要走一次 [String.format]（单次微秒级），
 * 而缓存键在缩略图扫描 / 取图路径上每条目要算多次（密码摘要 + 条目摘要 + 各候选尺寸），
 * 全量扫描下会累积成十万量级的 [String.format] 调用与中间字符串。查表编码产出与之
 * 完全一致的小写十六进制串，既有缓存键不失效。
 */
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

internal fun ByteArray.toLowerHex(): String {
    val out = CharArray(size * 2)
    var i = 0
    for (b in this) {
        val v = b.toInt() and 0xFF
        out[i++] = HEX_DIGITS[v ushr 4]
        out[i++] = HEX_DIGITS[v and 0x0F]
    }
    return String(out)
}
