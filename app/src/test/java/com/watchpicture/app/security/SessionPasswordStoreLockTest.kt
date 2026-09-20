package com.watchpicture.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionPasswordStoreLockTest {

    private lateinit var store: SessionPasswordStore
    private val packId = "pack_001"
    private val aliasPath = "/sdcard/Downloads/pack_001.zip"

    @Before
    fun setUp() {
        store = SessionPasswordStore()
    }

    @Test
    fun `marking pack explicitly locked revokes password and records lock state`() {
        store.set(packId, "Secret123", listOf(aliasPath))
        assertTrue(store.hasPassword(packId))
        assertTrue(store.hasPassword(aliasPath))
        assertFalse(store.isExplicitlyLocked(packId))

        // User locks the pack
        store.markExplicitlyLocked(packId)

        assertTrue("Must be recorded as explicitly locked", store.isExplicitlyLocked(packId))
        assertTrue("Alias must also resolve as explicitly locked", store.isExplicitlyLocked(aliasPath))
        assertFalse("Password must be removed", store.hasPassword(packId))
        assertNull("Password must not be obtainable", store.get(packId))
    }

    @Test
    fun `authenticating unlocked pack clears explicitly locked flag`() {
        store.markExplicitlyLocked(packId)
        assertTrue(store.isExplicitlyLocked(packId))

        store.set(packId, "NewPassword")
        assertFalse("Setting password must clear explicitly locked state", store.isExplicitlyLocked(packId))
        assertEquals("NewPassword", store.get(packId))
    }
}
