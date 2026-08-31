package com.sieve.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sieve.app.ui.settings.SettingsViewModel
import com.sieve.app.ui.theme.ThemeMode
import com.sieve.engine.repo.AnalyzeOutcome
import com.sieve.engine.repo.EngineEvent
import com.sieve.engine.repo.YtDlpEngine
import com.sieve.engine.update.UpdateChannel
import com.sieve.engine.update.UpdateCheck
import com.sieve.engine.update.UpdateResult
import com.sieve.storage.settings.StorageSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeEngine : YtDlpEngine {
        override suspend fun analyze(url: String, cookiesBrowser: String?): AnalyzeOutcome = throw NotImplementedError()
        override fun download(id: String, url: String, args: List<String>): Flow<EngineEvent> = emptyFlow()
        override fun cancel(id: String): Boolean = true
        override suspend fun version(): String? = "2025.01.01"
        override suspend fun checkUpdate(): UpdateCheck = throw NotImplementedError()
        override suspend fun doUpdate(channel: UpdateChannel): UpdateResult = throw NotImplementedError()
    }

    @Test
    fun settersPersistThroughAppSettings() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        ) { File(tmp.newFolder(), "s.preferences_pb") }
        val app = AppSettings(store)
        val storage = StorageSettings(store)
        val vm = SettingsViewModel(app, storage, FakeEngine())

        vm.setThemeMode(ThemeMode.LIGHT)
        vm.setMaxDownloads(5)
        vm.setDefaultPreset("best-1080")
        vm.setAccent("#4EC9A8")
        advanceUntilIdle()

        val prefs = app.flow.first()
        assertEquals(ThemeMode.LIGHT, prefs.themeMode)
        assertEquals(5, prefs.maxDownloads)
        assertEquals("best-1080", prefs.defaultPresetId)
        assertEquals("#4EC9A8", prefs.accentHex)
        Dispatchers.resetMain()
    }
}
