package com.watchpicture.app.storage

import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.watchpicture.app.model.SortOption
import com.watchpicture.app.ui.theme.applyAmoledColors
import com.watchpicture.app.ui.theme.resolveColorSchemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.darkColorScheme

class PreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val KEY_LAST_ROOT_URI = stringPreferencesKey("last_root_uri")
    private val KEY_SORT_OPTION = stringPreferencesKey("sort_option")
    private val KEY_READING_MODE = stringPreferencesKey("reading_mode")
    private val KEY_THEME_MODE_INDEX = intPreferencesKey("theme_mode_index")
    private val KEY_AMOLED_DARK = booleanPreferencesKey("amoled_dark")
    private val KEY_WIDE_SCREEN_RAIL = booleanPreferencesKey("wide_screen_rail")

    @Test
    fun `reads and writes preferences correctly in datastore`() = runTest {
        val testDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("test_settings.preferences_pb") }
        )

        // Initial default checks
        val initialUri = testDataStore.data.first()[KEY_LAST_ROOT_URI]
        assertNull(initialUri)

        // Write last root uri
        testDataStore.edit { it[KEY_LAST_ROOT_URI] = "content://tree/primary:Pictures" }
        assertEquals("content://tree/primary:Pictures", testDataStore.data.first()[KEY_LAST_ROOT_URI])

        // Write sort option
        testDataStore.edit { it[KEY_SORT_OPTION] = SortOption.TIME_DESC.name }
        assertEquals(SortOption.TIME_DESC.name, testDataStore.data.first()[KEY_SORT_OPTION])

        // Write reading mode
        testDataStore.edit { it[KEY_READING_MODE] = ReadingMode.RTL.name }
        assertEquals(ReadingMode.RTL.name, testDataStore.data.first()[KEY_READING_MODE])

        val keyAutoUpdate = booleanPreferencesKey("auto_check_update")
        val keyIgnoredVer = stringPreferencesKey("ignored_version")

        // Auto update defaults to true if unset
        val autoUpdateDefault = testDataStore.data.first()[keyAutoUpdate] ?: true
        assertEquals(true, autoUpdateDefault)

        // Write auto update preference
        testDataStore.edit { it[keyAutoUpdate] = false }
        assertEquals(false, testDataStore.data.first()[keyAutoUpdate])

        // Write ignored version
        testDataStore.edit { it[keyIgnoredVer] = "v1.2.0" }
        assertEquals("v1.2.0", testDataStore.data.first()[keyIgnoredVer])

        val keyStandaloneArchives = androidx.datastore.preferences.core.stringSetPreferencesKey("standalone_archives")
        val initialArchives = testDataStore.data.first()[keyStandaloneArchives] ?: emptySet()
        assertEquals(emptySet<String>(), initialArchives)

        testDataStore.edit {
            it[keyStandaloneArchives] = setOf("content://com.android.providers.media.documents/document/123", "file:///storage/emulated/0/test.zip")
        }
        val savedArchives = testDataStore.data.first()[keyStandaloneArchives]
        assertEquals(setOf("content://com.android.providers.media.documents/document/123", "file:///storage/emulated/0/test.zip"), savedArchives)

        // Theme mode index (default 3 = MonetSystem)
        val defaultThemeMode = testDataStore.data.first()[KEY_THEME_MODE_INDEX] ?: 3
        assertEquals(3, defaultThemeMode)
        testDataStore.edit { it[KEY_THEME_MODE_INDEX] = 5 }
        assertEquals(5, testDataStore.data.first()[KEY_THEME_MODE_INDEX])

        // AMOLED dark mode (default false)
        val defaultAmoled = testDataStore.data.first()[KEY_AMOLED_DARK] ?: false
        assertEquals(false, defaultAmoled)
        testDataStore.edit { it[KEY_AMOLED_DARK] = true }
        assertEquals(true, testDataStore.data.first()[KEY_AMOLED_DARK])

        // Wide screen navigation rail (default true)
        val defaultWideRail = testDataStore.data.first()[KEY_WIDE_SCREEN_RAIL] ?: true
        assertEquals(true, defaultWideRail)
        testDataStore.edit { it[KEY_WIDE_SCREEN_RAIL] = false }
        assertEquals(false, testDataStore.data.first()[KEY_WIDE_SCREEN_RAIL])
    }

    @Test
    fun `resolveColorSchemeMode maps indices and fallbacks accurately`() {
        assertEquals(ColorSchemeMode.System, resolveColorSchemeMode(0))
        assertEquals(ColorSchemeMode.Light, resolveColorSchemeMode(1))
        assertEquals(ColorSchemeMode.Dark, resolveColorSchemeMode(2))
        assertEquals(ColorSchemeMode.MonetSystem, resolveColorSchemeMode(3))
        assertEquals(ColorSchemeMode.MonetLight, resolveColorSchemeMode(4))
        assertEquals(ColorSchemeMode.MonetDark, resolveColorSchemeMode(5))
        assertEquals(ColorSchemeMode.MonetSystem, resolveColorSchemeMode(-1))
        assertEquals(ColorSchemeMode.MonetSystem, resolveColorSchemeMode(6))
        assertEquals(ColorSchemeMode.MonetSystem, resolveColorSchemeMode(99))
    }

    @Test
    fun `applyAmoledColors overrides surface roles to black hierarchy while keeping accent colors`() {
        val base = darkColorScheme()
        val amoled = applyAmoledColors(base)

        assertEquals(Color.Black, amoled.background)
        assertEquals(Color.Black, amoled.surface)
        assertEquals(Color(0xFF0D0D0D), amoled.surfaceContainer)
        assertEquals(Color(0xFF1A1A1A), amoled.surfaceContainerHigh)
        assertEquals(Color(0xFF262626), amoled.surfaceContainerHighest)
        assertEquals(base.primary, amoled.primary)
        assertEquals(base.onSurface, amoled.onSurface)
    }
}
