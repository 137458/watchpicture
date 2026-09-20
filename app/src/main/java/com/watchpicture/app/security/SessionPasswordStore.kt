package com.watchpicture.app.security

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory thread-safe storage for session passwords.
 * Passwords are never persisted to disk (SharedPreferences, DataStore, etc.).
 * When exiting a pack or switching, the cached password can be revoked immediately.
 */
class SessionPasswordStore {

    private val cache = ConcurrentHashMap<String, String>()
    @Volatile
    var lastUsedPassword: String? = null
        private set

    fun get(packId: String): String? {
        return cache[packId]
    }

    fun set(packId: String, password: String) {
        cache[packId] = password
        lastUsedPassword = password
    }

    fun remove(packId: String) {
        cache.remove(packId)
    }

    fun clear() {
        cache.clear()
        lastUsedPassword = null
    }

    fun hasPassword(packId: String): Boolean {
        return cache.containsKey(packId)
    }
}
