package com.watchpicture.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionPasswordStoreTest {

    private lateinit var store: SessionPasswordStore

    @Before
    fun setUp() {
        store = SessionPasswordStore()
    }

    @Test
    fun `store and retrieve password for specific pack`() {
        assertNull(store.get("pack_1"))
        assertFalse(store.hasPassword("pack_1"))

        store.set("pack_1", "secret123")

        assertEquals("secret123", store.get("pack_1"))
        assertTrue(store.hasPassword("pack_1"))
    }

    @Test
    fun `passwords for different packs are isolated`() {
        store.set("pack_1", "pass1")
        store.set("pack_2", "pass2")

        assertEquals("pass1", store.get("pack_1"))
        assertEquals("pass2", store.get("pack_2"))

        store.remove("pack_1")

        assertNull(store.get("pack_1"))
        assertEquals("pass2", store.get("pack_2"))
    }

    @Test
    fun `clear removes all cached session passwords`() {
        store.set("pack_1", "pass1")
        store.set("pack_2", "pass2")
        store.set("pack_3", "pass3")

        store.clear()

        assertNull(store.get("pack_1"))
        assertNull(store.get("pack_2"))
        assertNull(store.get("pack_3"))
        assertFalse(store.hasPassword("pack_1"))
    }

    @Test
    fun `updating password replaces existing value`() {
        store.set("pack_1", "initial")
        assertEquals("initial", store.get("pack_1"))

        store.set("pack_1", "updated")
        assertEquals("updated", store.get("pack_1"))
    }

    @Test
    fun `tracks and clears last used password`() {
        assertNull(store.lastUsedPassword)
        store.set("pack_1", "pwd_A")
        assertEquals("pwd_A", store.lastUsedPassword)

        store.set("pack_2", "pwd_B")
        assertEquals("pwd_B", store.lastUsedPassword)

        store.clear()
        assertNull(store.lastUsedPassword)
    }

    @Test
    fun `supports alias mapping between uri and direct file path`() {
        val uriKey = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fcomic.zip"
        val directPathKey = "/storage/emulated/0/Download/comic.zip"

        store.set(uriKey, "P@ssword999", aliases = listOf(directPathKey))

        // Both keys can retrieve the same password
        assertEquals("P@ssword999", store.get(uriKey))
        assertEquals("P@ssword999", store.get(directPathKey))
        assertTrue(store.hasPassword(uriKey))
        assertTrue(store.hasPassword(directPathKey))

        // Removing by one key revokes password across all associated aliases
        store.remove(uriKey)
        assertNull(store.get(uriKey))
        assertNull(store.get(directPathKey))
    }
}
