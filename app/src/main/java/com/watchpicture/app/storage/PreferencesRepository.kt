package com.watchpicture.app.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.watchpicture.app.model.SortOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

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
        private val KEY_AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_update")
        private val KEY_IGNORED_VERSION = stringPreferencesKey("ignored_version")
        private val KEY_STANDALONE_ARCHIVES = stringSetPreferencesKey("standalone_archives")
        private val KEY_THEME_MODE_INDEX = intPreferencesKey("theme_mode_index")
        private val KEY_AMOLED_DARK = booleanPreferencesKey("amoled_dark")
        private val KEY_WIDE_SCREEN_RAIL = booleanPreferencesKey("wide_screen_rail")
    }

    val lastRootUriFlow: Flow<String?> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[KEY_LAST_ROOT_URI]
        }

    val sortOptionFlow: Flow<SortOption> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            val raw = preferences[KEY_SORT_OPTION] ?: SortOption.NAME_ASC.name
            try {
                SortOption.valueOf(raw)
            } catch (_: Exception) {
                SortOption.NAME_ASC
            }
        }

    val readingModeFlow: Flow<ReadingMode> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            val raw = preferences[KEY_READING_MODE] ?: ReadingMode.LTR.name
            try {
                ReadingMode.valueOf(raw)
            } catch (_: Exception) {
                ReadingMode.LTR
            }
        }

    val autoCheckUpdateFlow: Flow<Boolean> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[KEY_AUTO_CHECK_UPDATE] ?: true
        }

    val ignoredVersionFlow: Flow<String?> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[KEY_IGNORED_VERSION]
        }

    val themeModeIndexFlow: Flow<Int> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[KEY_THEME_MODE_INDEX] ?: 3
        }

    val amoledDarkFlow: Flow<Boolean> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[KEY_AMOLED_DARK] ?: false
        }

    val wideScreenRailFlow: Flow<Boolean> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[KEY_WIDE_SCREEN_RAIL] ?: true
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

    suspend fun saveThemeModeIndex(index: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_THEME_MODE_INDEX] = index
        }
    }

    suspend fun saveAmoledDark(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_AMOLED_DARK] = enabled
        }
    }

    suspend fun saveWideScreenRail(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_WIDE_SCREEN_RAIL] = enabled
        }
    }

    val standaloneArchivesFlow: Flow<Set<String>> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
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
