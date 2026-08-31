package com.sieve.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sieve.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class AppSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { File(tmp.newFolder(), "app.preferences_pb") }

    @Test
    fun defaultsWhenEmpty() = runBlocking {
        val s = AppSettings(store())
        val d = s.flow.first()
        assertEquals(ThemeMode.DARK, d.themeMode)
        assertEquals("#E0A458", d.accentHex)
        assertEquals("best-video", d.defaultPresetId)
        assertEquals(3, d.maxDownloads)
        assertEquals(1, d.maxTranscodes)
        assertNull(d.proxy)
        Unit
    }

    @Test
    fun roundTripsMutations() = runBlocking {
        val s = AppSettings(store())
        s.setThemeMode(ThemeMode.LIGHT)
        s.setAccentHex("#4EC9A8")
        s.setDefaultPreset("best-1080")
        s.setMaxDownloads(5)
        s.setProxy("http://127.0.0.1:8080")
        val r = s.flow.first()
        assertEquals(ThemeMode.LIGHT, r.themeMode)
        assertEquals("#4EC9A8", r.accentHex)
        assertEquals("best-1080", r.defaultPresetId)
        assertEquals(5, r.maxDownloads)
        assertEquals("http://127.0.0.1:8080", r.proxy)
        Unit
    }

    @Test
    fun clampsAndClearsNullable() = runBlocking {
        val s = AppSettings(store())
        s.setMaxDownloads(99)        // clamps to 10
        s.setProxy("x"); s.setProxy(null)  // clears
        val r = s.flow.first()
        assertEquals(10, r.maxDownloads)
        assertNull(r.proxy)
        Unit
    }
}
