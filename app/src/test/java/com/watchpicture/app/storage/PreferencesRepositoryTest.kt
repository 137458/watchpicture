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
import com.watchpicture.app.ui.theme.themeAppearanceLabel
import com.watchpicture.app.ui.theme.themeColorSourceLabel
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
    fun `resolveColorSchemeMode maps darkMode and colorMode matrix accurately`() {
        // Legacy single-arg mapping
        assertEquals(ColorSchemeMode.System, resolveColorSchemeMode(0))
        assertEquals(ColorSchemeMode.Light, resolveColorSchemeMode(1))
        assertEquals(ColorSchemeMode.Dark, resolveColorSchemeMode(2))
        assertEquals(ColorSchemeMode.MonetSystem, resolveColorSchemeMode(3))
        assertEquals(ColorSchemeMode.MonetLight, resolveColorSchemeMode(4))
        assertEquals(ColorSchemeMode.MonetDark, resolveColorSchemeMode(5))

        // Decoupled (darkMode, colorMode): colorMode 0 (Monet wallpaper) & 2 (Seed color) -> Monet*
        assertEquals(ColorSchemeMode.MonetSystem, resolveColorSchemeMode(darkMode = 0, colorMode = 0))
        assertEquals(ColorSchemeMode.MonetLight, resolveColorSchemeMode(darkMode = 1, colorMode = 0))
        assertEquals(ColorSchemeMode.MonetDark, resolveColorSchemeMode(darkMode = 2, colorMode = 0))

        assertEquals(ColorSchemeMode.System, resolveColorSchemeMode(darkMode = 0, colorMode = 1))
        assertEquals(ColorSchemeMode.Light, resolveColorSchemeMode(darkMode = 1, colorMode = 1))
        assertEquals(ColorSchemeMode.Dark, resolveColorSchemeMode(darkMode = 2, colorMode = 1))

        assertEquals(ColorSchemeMode.MonetSystem, resolveColorSchemeMode(darkMode = 0, colorMode = 2))
        assertEquals(ColorSchemeMode.MonetLight, resolveColorSchemeMode(darkMode = 1, colorMode = 2))
        assertEquals(ColorSchemeMode.MonetDark, resolveColorSchemeMode(darkMode = 2, colorMode = 2))

        assertEquals(ColorSchemeMode.MonetSystem, resolveColorSchemeMode(darkMode = 99, colorMode = 99))
    }

    @Test
    fun `buildMiuixThemeController configures keyColor paletteStyle and colorSpec`() {
        val seedHex = 0xFF7B1FA2L
        val customSeedController = com.watchpicture.app.ui.theme.buildMiuixThemeController(
            darkMode = 2,
            colorMode = 2,
            seedColor = seedHex,
            amoledDark = true,
            paletteStyleIndex = 3, // Expressive
            useSpec2025 = true
        )
        assertEquals(ColorSchemeMode.MonetDark, customSeedController.colorSchemeMode)
        assertEquals(Color(seedHex), customSeedController.keyColor)
        assertEquals(top.yukonga.miuix.kmp.theme.ThemePaletteStyle.Expressive, customSeedController.paletteStyle)
        assertEquals(top.yukonga.miuix.kmp.theme.ThemeColorSpec.Spec2025, customSeedController.colorSpec)

        val monetWallpaperController = com.watchpicture.app.ui.theme.buildMiuixThemeController(
            darkMode = 0,
            colorMode = 0,
            seedColor = seedHex,
            amoledDark = false,
            paletteStyleIndex = -1, // out of bounds -> TonalSpot
            useSpec2025 = false
        )
        assertEquals(ColorSchemeMode.MonetSystem, monetWallpaperController.colorSchemeMode)
        assertNull(monetWallpaperController.keyColor)
        assertEquals(top.yukonga.miuix.kmp.theme.ThemePaletteStyle.TonalSpot, monetWallpaperController.paletteStyle)
        assertEquals(top.yukonga.miuix.kmp.theme.ThemeColorSpec.Spec2021, monetWallpaperController.colorSpec)
    }

    @Test
    fun `defaultLightColors separates page canvas from white card containers`() {
        val light = com.watchpicture.app.ui.theme.defaultLightColors()
        assertEquals(Color(0xFFF6F7F9), light.background)
        assertEquals(Color(0xFFF6F7F9), light.surface)
        assertEquals(Color.White, light.surfaceContainer)
        assertEquals(Color(0xFFF0F1F4), light.surfaceContainerHigh)
        assertEquals(Color(0xFFE5E7EB), light.surfaceContainerHighest)
    }

    @Test
    fun `applyAmoledColors overrides surface roles to pure black hierarchy while keeping accent colors`() {
        val base = darkColorScheme()
        val amoled = applyAmoledColors(base)

        assertEquals(Color.Black, amoled.background)
        assertEquals(Color.Black, amoled.surface)
        assertEquals(Color(0xFF121212), amoled.surfaceVariant)
        assertEquals(Color.Black, amoled.surfaceContainer)
        assertEquals(Color(0xFF1E1E1E), amoled.surfaceContainerHigh)
        assertEquals(Color(0xFF2C2C2C), amoled.surfaceContainerHighest)
        assertEquals(base.primary, amoled.primary)
        assertEquals(base.onSurface, amoled.onSurface)
    }

    @Test
    fun `resolveDefaultColorMode defaults to the fixed default palette when nothing was ever saved`() {
        // 全新安装（无 theme_mode_index）必须落到默认固定色板，而不是动态取色
        assertEquals(1, resolveDefaultColorMode(legacyThemeModeIndex = null))

        // 旧版显式选择过浅色/深色/跟随系统的固定配色，保持默认色板
        assertEquals(1, resolveDefaultColorMode(legacyThemeModeIndex = 0))
        assertEquals(1, resolveDefaultColorMode(legacyThemeModeIndex = 1))
        assertEquals(1, resolveDefaultColorMode(legacyThemeModeIndex = 2))

        // 旧版显式选择过莫奈取色的用户保持动态色彩，不被强行改写
        assertEquals(0, resolveDefaultColorMode(legacyThemeModeIndex = 3))
        assertEquals(0, resolveDefaultColorMode(legacyThemeModeIndex = 4))
        assertEquals(0, resolveDefaultColorMode(legacyThemeModeIndex = 5))

        // 越界脏数据同样回落默认固定色板
        assertEquals(1, resolveDefaultColorMode(legacyThemeModeIndex = 99))
        assertEquals(1, resolveDefaultColorMode(legacyThemeModeIndex = -1))
    }

    @Test
    fun `resolveDefaultDarkMode follows system and honours legacy light dark selections`() {
        assertEquals(0, resolveDefaultDarkMode(legacyThemeModeIndex = null))
        assertEquals(0, resolveDefaultDarkMode(legacyThemeModeIndex = 0))
        assertEquals(1, resolveDefaultDarkMode(legacyThemeModeIndex = 1))
        assertEquals(2, resolveDefaultDarkMode(legacyThemeModeIndex = 2))
        assertEquals(1, resolveDefaultDarkMode(legacyThemeModeIndex = 4))
        assertEquals(2, resolveDefaultDarkMode(legacyThemeModeIndex = 5))
        assertEquals(0, resolveDefaultDarkMode(legacyThemeModeIndex = 99))
    }

    @Test
    fun `theme summary labels describe appearance and colour source for the settings entry`() {
        assertEquals("跟随系统", themeAppearanceLabel(0))
        assertEquals("浅色", themeAppearanceLabel(1))
        assertEquals("深色", themeAppearanceLabel(2))
        assertEquals("跟随系统", themeAppearanceLabel(42))

        assertEquals("动态色彩", themeColorSourceLabel(colorMode = 0, seedColor = 0xFF2196F3L))
        assertEquals("默认色板", themeColorSourceLabel(colorMode = 1, seedColor = 0xFF2196F3L))
        // 预置种子色回显中文色名
        assertEquals("紫色", themeColorSourceLabel(colorMode = 2, seedColor = 0xFF7B1FA2L))
        assertEquals("金黄", themeColorSourceLabel(colorMode = 2, seedColor = 0xFFF9A825L))
        // 非预置色值视为自定义取色
        assertEquals("自定义", themeColorSourceLabel(colorMode = 2, seedColor = 0xFF123456L))
    }
}
