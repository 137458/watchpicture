package com.watchpicture.app.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
 * 外观深浅模式默认值: 0=跟随系统, 1=浅色, 2=深色。
 * 未显式保存过时，从旧版单一索引 theme_mode_index 平滑派生。
 */
internal fun resolveDefaultDarkMode(legacyThemeModeIndex: Int?): Int = when (legacyThemeModeIndex) {
    1, 4 -> 1
    2, 5 -> 2
    else -> 0
}

/**
 * 色彩来源默认值: 0=动态色彩(壁纸莫奈取色), 1=默认固定色板, 2=预置/自定义种子色。
 * 未显式保存过时，仅当旧版索引明确指向莫奈模式(3/4/5)才沿用动态取色，其余一律回落默认固定色板。
 */
internal fun resolveDefaultColorMode(legacyThemeModeIndex: Int?): Int = when (legacyThemeModeIndex) {
    0, 1, 2 -> 1
    3, 4, 5 -> 0
    else -> 1
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
        private val KEY_DARK_MODE = intPreferencesKey("dark_mode")
        private val KEY_COLOR_MODE = intPreferencesKey("color_mode")
        private val KEY_SEED_COLOR = longPreferencesKey("seed_color")
        private val KEY_PALETTE_STYLE_INDEX = intPreferencesKey("palette_style_index")
        private val KEY_USE_SPEC_2025 = booleanPreferencesKey("use_spec_2025")
        private val KEY_AMOLED_DARK = booleanPreferencesKey("amoled_dark")
        private val KEY_WIDE_SCREEN_RAIL = booleanPreferencesKey("wide_screen_rail")
        private val KEY_AUTO_CLEAN_CACHE = booleanPreferencesKey("auto_clean_cache")
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

    /**
     * 外观深浅模式: 0=跟随系统, 1=浅色, 2=深色。
     */
    val darkModeFlow: Flow<Int> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[KEY_DARK_MODE] ?: resolveDefaultDarkMode(preferences[KEY_THEME_MODE_INDEX])
        }

    /**
     * 色彩模式: 0=动态色彩(壁纸莫奈取色), 1=默认固定色板, 2=预置/自定义种子色。
     */
    val colorModeFlow: Flow<Int> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[KEY_COLOR_MODE] ?: resolveDefaultColorMode(preferences[KEY_THEME_MODE_INDEX])
        }

    val seedColorFlow: Flow<Long> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[KEY_SEED_COLOR] ?: 0xFF2196F3L
        }

    val paletteStyleIndexFlow: Flow<Int> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[KEY_PALETTE_STYLE_INDEX] ?: 0
        }

    val useSpec2025Flow: Flow<Boolean> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[KEY_USE_SPEC_2025] ?: false
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

    /**
     * 是否在启动与退到后台时自动把磁盘缓存裁剪到容量预算内（默认开启）。
     */
    val autoCleanCacheFlow: Flow<Boolean> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[KEY_AUTO_CLEAN_CACHE] ?: true
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

    suspend fun saveDarkMode(mode: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_DARK_MODE] = mode.coerceIn(0, 2)
        }
    }

    suspend fun saveColorMode(mode: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_COLOR_MODE] = mode.coerceIn(0, 2)
        }
    }

    suspend fun saveSeedColor(color: Long) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_SEED_COLOR] = color
        }
    }

    suspend fun savePaletteStyleIndex(index: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_PALETTE_STYLE_INDEX] = index.coerceAtLeast(0)
        }
    }

    suspend fun saveUseSpec2025(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_USE_SPEC_2025] = enabled
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

    suspend fun saveAutoCleanCache(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_AUTO_CLEAN_CACHE] = enabled
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
