package com.watchpicture.app.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.watchpicture.app.model.SortOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "watchpicture_settings")

enum class ReadingMode {
    LTR,
    RTL
}

/**
 * DataStore-backed repository storing user preferences including
 * the last authorized directory URI, preferred sorting order, and reading direction.
 */
class PreferencesRepository(private val context: Context) {

    companion object {
        private val KEY_LAST_ROOT_URI = stringPreferencesKey("last_root_uri")
        private val KEY_SORT_OPTION = stringPreferencesKey("sort_option")
        private val KEY_READING_MODE = stringPreferencesKey("reading_mode")
        private val KEY_AUTO_CHECK_UPDATE = androidx.datastore.preferences.core.booleanPreferencesKey("auto_check_update")
        private val KEY_IGNORED_VERSION = stringPreferencesKey("ignored_version")
        private val KEY_STANDALONE_ARCHIVES = androidx.datastore.preferences.core.stringSetPreferencesKey("standalone_archives")
    }

    val lastRootUriFlow: Flow<String?> = context.settingsDataStore.data.map { preferences ->
        preferences[KEY_LAST_ROOT_URI]
    }

    val sortOptionFlow: Flow<SortOption> = context.settingsDataStore.data.map { preferences ->
        val raw = preferences[KEY_SORT_OPTION] ?: SortOption.NAME_ASC.name
        try {
            SortOption.valueOf(raw)
        } catch (_: Exception) {
            SortOption.NAME_ASC
        }
    }

    val readingModeFlow: Flow<ReadingMode> = context.settingsDataStore.data.map { preferences ->
        val raw = preferences[KEY_READING_MODE] ?: ReadingMode.LTR.name
        try {
            ReadingMode.valueOf(raw)
        } catch (_: Exception) {
            ReadingMode.LTR
        }
    }

    val autoCheckUpdateFlow: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[KEY_AUTO_CHECK_UPDATE] ?: true
    }

    val ignoredVersionFlow: Flow<String?> = context.settingsDataStore.data.map { preferences ->
        preferences[KEY_IGNORED_VERSION]
    }

    suspend fun saveLastRootUri(uriString: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_LAST_ROOT_URI] = uriString
        }
    }

    suspend fun saveSortOption(option: SortOption) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_SORT_OPTION] = option.name
        }
    }

    suspend fun saveReadingMode(mode: ReadingMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_READING_MODE] = mode.name
        }
    }

    suspend fun saveAutoCheckUpdate(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_AUTO_CHECK_UPDATE] = enabled
        }
    }

    suspend fun saveIgnoredVersion(version: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_IGNORED_VERSION] = version
        }
    }

    val standaloneArchivesFlow: Flow<Set<String>> = context.settingsDataStore.data.map { preferences ->
        preferences[KEY_STANDALONE_ARCHIVES] ?: emptySet()
    }

    suspend fun addStandaloneArchive(uriString: String) {
        context.settingsDataStore.edit { preferences ->
            val current = preferences[KEY_STANDALONE_ARCHIVES] ?: emptySet()
            preferences[KEY_STANDALONE_ARCHIVES] = current + uriString
        }
    }

    suspend fun removeStandaloneArchive(uriString: String) {
        context.settingsDataStore.edit { preferences ->
            val current = preferences[KEY_STANDALONE_ARCHIVES] ?: emptySet()
            preferences[KEY_STANDALONE_ARCHIVES] = current - uriString
        }
    }

    suspend fun saveStandaloneArchives(uris: Set<String>) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_STANDALONE_ARCHIVES] = uris
        }
    }
}
