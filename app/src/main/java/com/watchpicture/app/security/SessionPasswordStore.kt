package com.watchpicture.app.security

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory thread-safe storage for session passwords.
 * Passwords are never persisted to disk (SharedPreferences, DataStore, etc.).
 * When exiting a pack or switching, the cached password can be revoked immediately.
 */
class SessionPasswordStore {

    private val cache = ConcurrentHashMap<String, String>()
    private val aliasMap = ConcurrentHashMap<String, String>()
    private val keyAliases = ConcurrentHashMap<String, MutableSet<String>>()

    @Volatile
    var lastUsedPassword: String? = null
        private set

    fun get(packId: String): String? {
        cache[packId]?.let { return it }
        val canonicalKey = aliasMap[packId]
        if (canonicalKey != null) {
            cache[canonicalKey]?.let { return it }
        }
        return null
    }

    fun set(packId: String, password: String, aliases: List<String> = emptyList()) {
        cache[packId] = password
        lastUsedPassword = password

        val aliasSet = keyAliases.computeIfAbsent(packId) { ConcurrentHashMap.newKeySet() }
        for (alias in aliases) {
            if (alias.isNotBlank() && alias != packId) {
                aliasSet.add(alias)
                aliasMap[alias] = packId
                cache[alias] = password
            }
        }
    }

    fun remove(packId: String) {
        cache.remove(packId)
        val canonicalKey = aliasMap.remove(packId) ?: packId
        cache.remove(canonicalKey)

        val associated = keyAliases.remove(canonicalKey)
        associated?.forEach { alias ->
            aliasMap.remove(alias)
            cache.remove(alias)
        }
        keyAliases.remove(packId)?.forEach { alias ->
            aliasMap.remove(alias)
            cache.remove(alias)
        }
    }

    fun clear() {
        cache.clear()
        aliasMap.clear()
        keyAliases.clear()
        lastUsedPassword = null
    }

    fun hasPassword(packId: String): Boolean {
        return get(packId) != null
    }
}
