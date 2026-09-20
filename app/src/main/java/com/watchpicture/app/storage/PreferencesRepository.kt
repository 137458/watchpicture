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
}
