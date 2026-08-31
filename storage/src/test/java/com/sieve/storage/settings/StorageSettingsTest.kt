package com.sieve.storage.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class StorageSettingsTest {

    private fun newStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            ApplicationProvider.getApplicationContext<android.content.Context>().preferencesDataStoreFile(name)
        }

    @Test fun `defaults when empty`() = runTest {
        val s = StorageSettings(newStore("empty"))
        val p = s.prefs.first()
        assertNull(p.outputTreeUri)
        assertNull(p.outputDirLabelDefault)
        assertTrue(p.useMediaStore)
    }

    @Test fun `round-trips the tree uri and label`() = runTest {
        val s = StorageSettings(newStore("rt"))
        s.setOutputTree("content://x/tree/abc")
        s.setDefaultDirLabel("Music")
        s.setUseMediaStore(false)
        val p = s.prefs.first()
        assertEquals("content://x/tree/abc", p.outputTreeUri)
        assertEquals("Music", p.outputDirLabelDefault)
        assertEquals(false, p.useMediaStore)
    }

    @Test fun `clearing the tree uri stores null`() = runTest {
        val s = StorageSettings(newStore("clr"))
        s.setOutputTree("content://x/tree/abc")
        s.setOutputTree(null)
        assertNull(s.prefs.first().outputTreeUri)
    }
}
