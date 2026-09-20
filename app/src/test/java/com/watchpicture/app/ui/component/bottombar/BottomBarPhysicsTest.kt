package com.watchpicture.app.ui.component.bottombar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class BottomBarPhysicsTest {

    @Test
    fun testQuantizeGravityAngle_whenFlat_defaultsToMinusNinetyDegrees() {
        val gx = 0.05f
        val gy = 0.05f
        val angle = calculateQuantizedGravityAngle(gx, gy)
        val expected = (-PI / 2).toFloat()
        assertEquals(expected, angle, 1e-4f)
    }

    @Test
    fun testQuantizeGravityAngle_whenTilted_quantizesInThreeDegreeSteps() {
        val step = (3.0 * PI / 180.0).toFloat()

        // 45 degrees tilt
        val gx = 1f
        val gy = 1f
        val angle = calculateQuantizedGravityAngle(gx, gy)
        val remainder = abs(angle % step)
        assertTrue(
            "Angle $angle must be a multiple of 3 degrees ($step)",
            remainder < 1e-4f || abs(remainder - step) < 1e-4f
        )
    }

    @Test
    fun testVelocityStretch_whenZeroVelocity_returnsUnitScale() {
        val (stretchX, compressY) = calculateVelocityStretch(0f)
        assertEquals(1f, stretchX, 1e-4f)
        assertEquals(1f, compressY, 1e-4f)
    }

    @Test
    fun testVelocityStretch_whenHighVelocity_stretchesXAndCompressesY() {
        val (stretchX, compressY) = calculateVelocityStretch(10f)
        assertTrue("stretchX should be > 1.0 for positive velocity", stretchX.compareTo(1f) > 0)
        assertTrue("compressY should be < 1.0 for positive velocity", compressY.compareTo(1f) < 0)
    }

    @Test
    fun testRubberBandOffset_staysWithinBoundaries() {
        val maxOffset = 16f
        val offset = calculateRubberBandOffset(dragOffset = 1000f, totalWidth = 200f, rubberBandMaxPx = maxOffset)
        assertTrue("Rubber band offset must not exceed max boundary", offset <= maxOffset)
        assertTrue("Rubber band offset must be positive for positive drag", offset > 0f)
    }
}
