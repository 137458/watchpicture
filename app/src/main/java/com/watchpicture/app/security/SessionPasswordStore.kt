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
    private val explicitlyLockedPacks = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var lastUsedPassword: String? = null
        private set

    fun isExplicitlyLocked(packId: String): Boolean {
        if (explicitlyLockedPacks.contains(packId)) return true
        val canonicalKey = aliasMap[packId]
        if (canonicalKey != null && explicitlyLockedPacks.contains(canonicalKey)) return true
        return false
    }

    fun markExplicitlyLocked(packId: String) {
        explicitlyLockedPacks.add(packId)
        val canonicalKey = aliasMap[packId] ?: packId
        explicitlyLockedPacks.add(canonicalKey)
        keyAliases[canonicalKey]?.forEach { explicitlyLockedPacks.add(it) }
        keyAliases[packId]?.forEach { explicitlyLockedPacks.add(it) }
        remove(packId)
    }

    fun unlockExplicitlyLocked(packId: String) {
        explicitlyLockedPacks.remove(packId)
        val canonicalKey = aliasMap[packId]
        if (canonicalKey != null) {
            explicitlyLockedPacks.remove(canonicalKey)
        }
        keyAliases[packId]?.forEach { explicitlyLockedPacks.remove(it) }
    }

    fun get(packId: String): String? {
        if (isExplicitlyLocked(packId)) return null
        cache[packId]?.let { return it }
        val canonicalKey = aliasMap[packId]
        if (canonicalKey != null) {
            cache[canonicalKey]?.let { return it }
        }
        return null
    }

    fun set(packId: String, password: String, aliases: List<String> = emptyList()) {
        unlockExplicitlyLocked(packId)
        for (alias in aliases) {
            unlockExplicitlyLocked(alias)
        }
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
        explicitlyLockedPacks.clear()
        lastUsedPassword = null
        com.watchpicture.app.archive.SevenZKeyCache.clear()
    }

    fun hasPassword(packId: String): Boolean {
        return get(packId) != null
    }
}
