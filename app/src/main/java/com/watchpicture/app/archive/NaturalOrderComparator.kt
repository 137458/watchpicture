package com.watchpicture.app.archive

import java.math.BigInteger

/**
 * Natural order comparator for filenames with arbitrary numeric segments.
 * Correctly sorts "2.jpg" before "10.jpg", "page_01" before "page_02", etc.
 */
class NaturalOrderComparator : Comparator<String> {

    override fun compare(s1: String?, s2: String?): Int {
        if (s1 == null && s2 == null) return 0
        if (s1 == null) return -1
        if (s2 == null) return 1

        // 全角数字(０-９,U+FF10-FF19)在 CJK 图包文件名常见。isDigit() 认其为数字,
        // 但后续 trimStart('0')/char 比较均按 ASCII 语义,导致值比较错乱;先归一化。
        val a1 = normalizeFullWidthDigits(s1)
        val a2 = normalizeFullWidthDigits(s2)

        var i1 = 0
        var i2 = 0
        val len1 = a1.length
        val len2 = a2.length

        while (i1 < len1 && i2 < len2) {
            val c1 = a1[i1]
            val c2 = a2[i2]

            if (c1.isDigit() && c2.isDigit()) {
                // Parse full numeric chunk for both
                val start1 = i1
                while (i1 < len1 && a1[i1].isDigit()) {
                    i1++
                }
                val numStr1 = a1.substring(start1, i1)

                val start2 = i2
                while (i2 < len2 && a2[i2].isDigit()) {
                    i2++
                }
                val numStr2 = a2.substring(start2, i2)

                // Strip leading zeros for value comparison
                val trimmed1 = numStr1.trimStart('0')
                val trimmed2 = numStr2.trimStart('0')

                val numDiff = if (trimmed1.length != trimmed2.length) {
                    trimmed1.length - trimmed2.length
                } else {
                    trimmed1.compareTo(trimmed2)
                }

                if (numDiff != 0) {
                    return numDiff
                }

                // If values are numerically equal (e.g. "01" and "1"), compare length
                if (numStr1.length != numStr2.length) {
                    return numStr1.length - numStr2.length
                }
            } else {
                val comp = c1.lowercaseChar().compareTo(c2.lowercaseChar())
                if (comp != 0) {
                    return comp
                }
                i1++
                i2++
            }
        }

        return len1 - len2
    }

    private fun normalizeFullWidthDigits(s: String): String {
        // 快路径:绝大多数文件名不含全角数字,避免每次比较都产生新串
        var has = false
        for (c in s) {
            if (c in '０'..'９') {
                has = true
                break
            }
        }
        if (!has) return s
        return buildString(s.length) {
            for (c in s) {
                append(if (c in '０'..'９') '0' + (c - '０') else c)
            }
        }
    }
}
