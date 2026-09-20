package com.watchpicture.app.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.watchpicture.app.model.SortOption
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val KEY_LAST_ROOT_URI = stringPreferencesKey("last_root_uri")
    private val KEY_SORT_OPTION = stringPreferencesKey("sort_option")
    private val KEY_READING_MODE = stringPreferencesKey("reading_mode")

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
    }
}
