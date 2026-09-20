package com.watchpicture.app.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerThermalManagerTest {

    @Test
    fun `manual throttle level updates state flow and isThrottled flag`() {
        val manager = PowerThermalManager()

        assertEquals(PowerThermalManager.ThrottleLevel.NORMAL, manager.throttleLevel.value)
        assertFalse(manager.isThrottled)

        manager.setManualThrottleLevel(PowerThermalManager.ThrottleLevel.THROTTLED)
        assertEquals(PowerThermalManager.ThrottleLevel.THROTTLED, manager.throttleLevel.value)
        assertTrue(manager.isThrottled)

        manager.setManualThrottleLevel(PowerThermalManager.ThrottleLevel.NORMAL)
        assertEquals(PowerThermalManager.ThrottleLevel.NORMAL, manager.throttleLevel.value)
        assertFalse(manager.isThrottled)
    }
}
