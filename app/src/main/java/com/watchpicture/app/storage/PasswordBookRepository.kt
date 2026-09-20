package com.watchpicture.app.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.passwordBookDataStore: DataStore<Preferences> by preferencesDataStore(name = "watchpicture_password_book")

/**
 * DataStore-backed repository managing saved user passwords for quick unlocking of archives.
 */
class PasswordBookRepository(private val context: Context) {

    companion object {
        private val KEY_SAVED_PASSWORDS = stringPreferencesKey("saved_passwords_json")
        private val KEY_AUTO_SAVE_PASSWORD = booleanPreferencesKey("auto_save_password")
        private val json = Json { ignoreUnknownKeys = true }
    }

    val passwordsFlow: Flow<List<String>> = context.passwordBookDataStore.data.map { preferences ->
        val raw = preferences[KEY_SAVED_PASSWORDS] ?: "[]"
        try {
            json.decodeFromString<List<String>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    val autoSavePasswordFlow: Flow<Boolean> = context.passwordBookDataStore.data.map { preferences ->
        preferences[KEY_AUTO_SAVE_PASSWORD] ?: true
    }

    suspend fun addPassword(password: String) {
        val trimmed = password.trim()
        if (trimmed.isEmpty()) return

        context.passwordBookDataStore.edit { preferences ->
            val raw = preferences[KEY_SAVED_PASSWORDS] ?: "[]"
            val currentList = try {
                json.decodeFromString<List<String>>(raw).toMutableList()
            } catch (_: Exception) {
                mutableListOf()
            }

            // Remove if existing, insert at the front (most recent)
            currentList.remove(trimmed)
            currentList.add(0, trimmed)

            preferences[KEY_SAVED_PASSWORDS] = json.encodeToString(currentList)
        }
    }

    suspend fun removePassword(password: String) {
        context.passwordBookDataStore.edit { preferences ->
            val raw = preferences[KEY_SAVED_PASSWORDS] ?: "[]"
            val currentList = try {
                json.decodeFromString<List<String>>(raw).toMutableList()
            } catch (_: Exception) {
                mutableListOf()
            }

            currentList.remove(password)
            preferences[KEY_SAVED_PASSWORDS] = json.encodeToString(currentList)
        }
    }

    suspend fun clearAll() {
        context.passwordBookDataStore.edit { preferences ->
            preferences[KEY_SAVED_PASSWORDS] = "[]"
        }
    }

    suspend fun setAutoSavePassword(enabled: Boolean) {
        context.passwordBookDataStore.edit { preferences ->
            preferences[KEY_AUTO_SAVE_PASSWORD] = enabled
        }
    }
}
