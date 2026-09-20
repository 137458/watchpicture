package com.watchpicture.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PasswordBookRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var memoryRepo: InMemoryPasswordBookRepository

    @Before
    fun setUp() {
        memoryRepo = InMemoryPasswordBookRepository()
    }

    @Test
    fun `adding passwords deduplicates and preserves recent order`() {
        memoryRepo.addPassword("pwd1")
        memoryRepo.addPassword("pwd2")
        memoryRepo.addPassword("pwd1") // duplicate

        val passwords = memoryRepo.getPasswords()
        assertEquals(2, passwords.size)
        // Most recently added should be at front
        assertEquals("pwd1", passwords[0])
        assertEquals("pwd2", passwords[1])
    }

    @Test
    fun `removing password deletes target entry`() {
        memoryRepo.addPassword("pwd1")
        memoryRepo.addPassword("pwd2")
        memoryRepo.removePassword("pwd1")

        val passwords = memoryRepo.getPasswords()
        assertEquals(1, passwords.size)
        assertEquals("pwd2", passwords[0])
    }

    @Test
    fun `clearing removes all saved passwords`() {
        memoryRepo.addPassword("pwd1")
        memoryRepo.addPassword("pwd2")
        memoryRepo.clearAll()

        val passwords = memoryRepo.getPasswords()
        assertTrue(passwords.isEmpty())
    }
}

/**
 * Clean memory implementation for fast unit test verification
 */
class InMemoryPasswordBookRepository {
    private val passwords = mutableListOf<String>()

    fun getPasswords(): List<String> = passwords.toList()

    fun addPassword(password: String) {
        val trimmed = password.trim()
        if (trimmed.isEmpty()) return
        passwords.remove(trimmed)
        passwords.add(0, trimmed)
    }

    fun removePassword(password: String) {
        passwords.remove(password)
    }

    fun clearAll() {
        passwords.clear()
    }
}
