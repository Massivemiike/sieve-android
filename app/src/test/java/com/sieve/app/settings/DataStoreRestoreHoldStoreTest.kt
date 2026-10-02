package com.sieve.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sieve.queue.core.RestoreHold
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class DataStoreRestoreHoldStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { File(tmp.newFolder(), "app.preferences_pb") }

    @Test
    fun anEmptyStoreIsAnUpgradeWithNoMarker() = runBlocking {
        // A device that ran v1.0.3 or older has no marker: that is what makes its first restore a held one.
        assertEquals(RestoreHold(), DataStoreRestoreHoldStore(store()).load())
        Unit
    }

    @Test
    fun roundTripsTheWholeHold() = runBlocking {
        val s = DataStoreRestoreHoldStore(store())
        val hold = RestoreHold(migrated = true, heldIds = setOf("a", "b", "c"), bannerDismissed = true)
        s.save(hold)
        assertEquals(hold, s.load())
        Unit
    }

    @Test
    fun savingAnEmptyHoldClearsTheIds() = runBlocking {
        val s = DataStoreRestoreHoldStore(store())
        s.save(RestoreHold(migrated = true, heldIds = setOf("a")))
        s.save(RestoreHold(migrated = true))
        assertEquals(RestoreHold(migrated = true), s.load())
        Unit
    }

    @Test
    fun theHoldSharesTheSettingsFileWithoutTouchingTheirKeys() = runBlocking {
        val ds = store()
        ds.edit { it[stringPreferencesKey("theme_mode")] = "LIGHT" }
        DataStoreRestoreHoldStore(ds).save(RestoreHold(migrated = true, heldIds = setOf("a")))

        assertEquals("LIGHT", ds.data.first()[stringPreferencesKey("theme_mode")])
        assertEquals(setOf("a"), DataStoreRestoreHoldStore(ds).load().heldIds)
        Unit
    }
}
