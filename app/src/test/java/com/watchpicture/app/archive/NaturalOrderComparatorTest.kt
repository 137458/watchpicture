package com.watchpicture.app.archive

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderComparatorTest {

    private val comparator = NaturalOrderComparator()

    @Test
    fun `sorts numbers in filenames naturally instead of lexicographically`() {
        val input = listOf("10.jpg", "1.jpg", "2.jpg", "20.jpg", "3.jpg")
        val sorted = input.sortedWith(comparator)
        val expected = listOf("1.jpg", "2.jpg", "3.jpg", "10.jpg", "20.jpg")
        assertEquals(expected, sorted)
    }

    @Test
    fun `handles leading zeros and prefix correctly`() {
        val input = listOf("page_100.png", "page_01.png", "page_02.png", "page_10.png")
        val sorted = input.sortedWith(comparator)
        val expected = listOf("page_01.png", "page_02.png", "page_10.png", "page_100.png")
        assertEquals(expected, sorted)
    }

    @Test
    fun `handles multiple numeric segments`() {
        val input = listOf(
            "vol2_ch10_p01.jpg",
            "vol1_ch2_p10.jpg",
            "vol1_ch1_p02.jpg",
            "vol1_ch1_p1.jpg",
            "vol1_ch2_p2.jpg"
        )
        val sorted = input.sortedWith(comparator)
        val expected = listOf(
            "vol1_ch1_p1.jpg",
            "vol1_ch1_p02.jpg",
            "vol1_ch2_p2.jpg",
            "vol1_ch2_p10.jpg",
            "vol2_ch10_p01.jpg"
        )
        assertEquals(expected, sorted)
    }

    @Test
    fun `case insensitive comparison for letters`() {
        val input = listOf("b.jpg", "A.jpg", "c.jpg")
        val sorted = input.sortedWith(comparator)
        val expected = listOf("A.jpg", "b.jpg", "c.jpg")
        assertEquals(expected, sorted)
    }
}
