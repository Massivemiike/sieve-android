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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeEngine(
        /** When set, version() suspends until it completes, so a test can pick which Settings read finishes last. */
        private val versionGate: CompletableDeferred<Unit>? = null,
        private val update: () -> UpdateResult = { throw NotImplementedError() },
    ) : YtDlpEngine {
        var updateCalls = 0
        override suspend fun analyze(url: String, cookiesBrowser: String?, cookiesFile: String?): AnalyzeOutcome = throw NotImplementedError()
        override fun download(id: String, url: String, args: List<String>): Flow<EngineEvent> = emptyFlow()
        override fun cancel(id: String): Boolean = true
        override suspend fun version(): String? { versionGate?.await(); return "2025.01.01" }
        override suspend fun checkUpdate(): UpdateCheck = throw NotImplementedError()
        override suspend fun doUpdate(channel: UpdateChannel): UpdateResult { updateCalls++; return update() }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** The documents the "picker" can hand over, keyed by Uri string. */
    private val documents = mutableMapOf<String, ByteArray>()
    private val cookiesDir by lazy { tmp.newFolder("cookies") }
    private val cookiesStore by lazy { CookiesStore(cookiesDir, open = { documents[it]?.inputStream() }) }

    private lateinit var appSettings: AppSettings

    /** A VM whose viewModelScope runs eagerly on the test scheduler. */
    private fun TestScope.newVm(
        engine: YtDlpEngine,
        downloadActive: () -> Boolean = { false },
        io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ): SettingsViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        ) { File(tmp.newFolder(), "s.preferences_pb") }
        appSettings = AppSettings(store)
        return SettingsViewModel(
            appSettings, StorageSettings(store), engine, downloadActive,
            cookiesStore = cookiesStore, io = io,
        )
    }

    private val goodCookies = (
        "# Netscape HTTP Cookie File\n" +
            ".youtube.com\tTRUE\t/\tTRUE\t1893456000\tSID\tabc\n" +
            "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t1893456000\t__Secure-1PSID\tdef\n"
        ).toByteArray()

    @Test
    fun updateIsRefusedWhileADownloadRuns() = runTest {
        val engine = FakeEngine { UpdateResult(true, "DONE") }
        val vm = newVm(engine, downloadActive = { true })
        vm.updateEngine()
        val s = vm.state.first { it.updateMessage != null }
        assertEquals("Wait for downloads to finish before updating yt-dlp.", s.updateMessage)
        assertTrue(s.updateMessageIsError)                  // it is a refusal, shown in the error colour
        assertFalse(s.updating)
        assertEquals(0, engine.updateCalls)
    }

    @Test
    fun updateRunsWhenIdleAndReportsSuccess() = runTest {
        val engine = FakeEngine { UpdateResult(true, "DONE") }
        val vm = newVm(engine)
        vm.updateEngine()
        val s = vm.state.first { it.updateMessage != null }
        assertEquals("Engine updated", s.updateMessage)
        assertFalse(s.updateMessageIsError)
        assertFalse(s.updating)
        assertEquals(1, engine.updateCalls)
    }

    @Test
    fun updateReportsFailureWhenResultIsNotOk() = runTest {
        val engine = FakeEngine { UpdateResult(false, "failed to update youtube-dl") }
        val vm = newVm(engine)
        vm.updateEngine()
        val s = vm.state.first { it.updateMessage != null }
        assertTrue(s.updateMessage!!.startsWith("Update failed"), s.updateMessage)
        assertTrue("failed to update youtube-dl" in s.updateMessage!!, s.updateMessage)
        assertFalse(s.updateMessage!!.contains("Engine updated"))
        assertTrue(s.updateMessageIsError)
    }

    @Test
    fun updateReportsFailureWhenEngineThrows() = runTest {
        val engine = FakeEngine { throw IllegalStateException("boom") }
        val vm = newVm(engine)
        vm.updateEngine()
        val s = vm.state.first { it.updateMessage != null }
        assertEquals("Update failed: boom", s.updateMessage)
        assertTrue(s.updateMessageIsError)
    }

    @Test
    fun theUpdateMessageClearsOnDismissAndOnTheNextUpdate() = runTest {
        val vm = newVm(FakeEngine { UpdateResult(true, "DONE") }, downloadActive = { true })
        vm.updateEngine()
        vm.state.first { it.updateMessage != null }
        vm.dismissUpdateMessage()
        val cleared = vm.state.first { it.updateMessage == null }
        assertFalse(cleared.updateMessageIsError)                           // the colour flag goes with the text

        vm.updateEngine()                                                   // blocked again: the message is back...
        assertTrue(vm.state.first { it.updateMessage != null }.updateMessageIsError)
    }

    @Test
    fun updateFailureWithBlankDetailIsJustUpdateFailed() = runTest {
        val vm = newVm(FakeEngine { UpdateResult(false, "  ") })
        vm.updateEngine()
        assertEquals("Update failed", vm.state.first { it.updateMessage != null }.updateMessage)
    }

    @Test
    fun proxyIsValidatedTrimmedAndClearable() = runTest {
        val vm = newVm(FakeEngine())
        vm.setProxy("  socks5://127.0.0.1:1080 ")
        assertEquals("socks5://127.0.0.1:1080", appSettings.flow.first().proxy)
        vm.setProxy("not a proxy")                       // rejected: the stored value stays
        assertEquals("socks5://127.0.0.1:1080", appSettings.flow.first().proxy)
        vm.setProxy("")                                  // blank clears
        assertNull(appSettings.flow.first().proxy)
    }

    @Test
    fun userAgentKeepsItsSpacesButNotItsEdges() = runTest {
        val vm = newVm(FakeEngine())
        vm.setUserAgent("  Mozilla/5.0 (X11; Linux x86_64) Firefox/130.0 ")
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) Firefox/130.0", appSettings.flow.first().userAgent)
        vm.setUserAgent("bad\nagent")
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) Firefox/130.0", appSettings.flow.first().userAgent)
    }

    @Test
    fun speedLimitIsNormalizedAndZeroMeansUnlimited() = runTest {
        val vm = newVm(FakeEngine())
        vm.setSpeedLimit(" 2 m ")
        assertEquals("2M", appSettings.flow.first().speedLimit)
        vm.setSpeedLimit("fast")                         // rejected
        assertEquals("2M", appSettings.flow.first().speedLimit)
        vm.setSpeedLimit("0")
        assertNull(appSettings.flow.first().speedLimit)
    }

    @Test
    fun importingCookiesCopiesThemAndStoresTheAppPrivatePath() = runTest {
        documents["content://picker/cookies.txt"] = goodCookies
        val vm = newVm(FakeEngine())
        vm.importCookies("content://picker/cookies.txt")

        val s = vm.state.first { it.cookies != null }
        assertEquals(2, s.cookies!!.count)
        assertNull(s.cookiesMessage)
        val path = appSettings.flow.first().cookiesFileUri!!
        assertEquals(File(cookiesDir, "cookies.txt").absolutePath, path)   // a real path, never a content:// Uri
        assertTrue(File(path).isFile)
    }

    @Test
    fun aFileThatIsNotACookiesTxtIsRejectedWithAMessage() = runTest {
        documents["content://picker/notes.txt"] = "buy milk\nand eggs".toByteArray()
        val vm = newVm(FakeEngine())
        vm.importCookies("content://picker/notes.txt")

        val s = vm.state.first { it.cookiesMessage != null }
        assertEquals("That doesn't look like a cookies.txt (Netscape format).", s.cookiesMessage)
        assertNull(s.cookies)
        assertNull(appSettings.flow.first().cookiesFileUri)
        assertFalse(File(cookiesDir, "cookies.txt").exists())
    }

    @Test
    fun anUnreadablePickIsReportedNotThrown() = runTest {
        val vm = newVm(FakeEngine())
        vm.importCookies("content://picker/gone.txt")                       // no such document
        assertEquals("Couldn't read that file.", vm.state.first { it.cookiesMessage != null }.cookiesMessage)
        vm.dismissCookiesMessage()
        assertNull(vm.state.first { it.cookiesMessage == null }.cookiesMessage)
    }

    @Test
    fun aFailedReplaceKeepsTheWorkingCookies() = runTest {
        documents["content://picker/good.txt"] = goodCookies
        documents["content://picker/bad.txt"] = "{\"json\": true}".toByteArray()
        val vm = newVm(FakeEngine())
        vm.importCookies("content://picker/good.txt")
        vm.state.first { it.cookies != null }
        vm.importCookies("content://picker/bad.txt")

        val s = vm.state.first { it.cookiesMessage != null }
        assertEquals(2, s.cookies!!.count)                                  // still loaded
        assertTrue(File(cookiesDir, "cookies.txt").readText().contains("SID"))
    }

    @Test
    fun removingCookiesDeletesTheCopyAndTheSetting() = runTest {
        documents["content://picker/cookies.txt"] = goodCookies
        val vm = newVm(FakeEngine())
        vm.importCookies("content://picker/cookies.txt")
        vm.state.first { it.cookies != null }

        vm.removeCookies()
        assertNull(vm.state.first { it.cookies == null }.cookies)
        assertNull(appSettings.flow.first().cookiesFileUri)
        assertFalse(File(cookiesDir, "cookies.txt").exists())
    }

    @Test
    fun resetAlsoClearsNetworkSettingsAndCookies() = runTest {
        documents["content://picker/cookies.txt"] = goodCookies
        val vm = newVm(FakeEngine())
        vm.setProxy("http://10.0.0.1:8080"); vm.setUserAgent("UA"); vm.setSpeedLimit("1M")
        vm.importCookies("content://picker/cookies.txt")
        vm.state.first { it.cookies != null }

        vm.reset()
        val p = appSettings.flow.first()
        assertNull(p.proxy); assertNull(p.userAgent); assertNull(p.speedLimit); assertNull(p.cookiesFileUri)
        assertFalse(File(cookiesDir, "cookies.txt").exists())
    }

    @Test
    fun cookiesAlreadyOnDiskShowUpWhenSettingsOpens() = runTest {
        File(cookiesDir, "cookies.txt").writeText(String(goodCookies))
        val vm = newVm(FakeEngine())
        appSettings.setCookiesFileUri(File(cookiesDir, "cookies.txt").absolutePath)
        assertEquals(2, vm.state.first { it.cookies != null }.cookies!!.count)
    }

    @Test
    fun theEngineVersionAndTheCookiesBothSurviveWhenTheVersionResolvesLast() = runTest {
        File(cookiesDir, "cookies.txt").writeText(String(goodCookies))
        val gate = CompletableDeferred<Unit>()
        val vm = newVm(FakeEngine(versionGate = gate))                      // io is eager: the cookies read finishes first
        appSettings.setCookiesFileUri(File(cookiesDir, "cookies.txt").absolutePath)
        vm.state.first { it.cookies != null }

        gate.complete(Unit)                                                 // ...then the version lands
        val s = vm.state.first { it.engineVersion != null }
        assertEquals("2025.01.01", s.engineVersion)
        assertEquals(2, s.cookies?.count)                                   // not overwritten by the version's stale copy
    }

    @Test
    fun theEngineVersionAndTheCookiesBothSurviveWhenTheCookiesResolveLast() = runTest {
        File(cookiesDir, "cookies.txt").writeText(String(goodCookies))
        val gate = CompletableDeferred<Unit>()
        // Both reads are pending: the version on the gate, the cookies on the queued io dispatcher.
        val vm = newVm(FakeEngine(gate), io = StandardTestDispatcher(testScheduler))
        appSettings.setCookiesFileUri(File(cookiesDir, "cookies.txt").absolutePath)

        gate.complete(Unit)                                                 // the version lands first...
        vm.state.first { it.engineVersion != null }
        advanceUntilIdle()                                                  // ...then the cookies read completes
        val s = vm.state.first { it.cookies != null }
        assertEquals("2025.01.01", s.engineVersion)                         // not overwritten by the cookies' stale copy
        assertEquals(2, s.cookies?.count)
    }

    @Test
    fun theVersionRefreshAfterAnUpdateKeepsTheCookies() = runTest {
        documents["content://picker/cookies.txt"] = goodCookies
        val gate = CompletableDeferred<Unit>()
        val vm = newVm(FakeEngine(gate) { UpdateResult(true, "DONE") })
        vm.importCookies("content://picker/cookies.txt")
        gate.complete(Unit)
        vm.state.first { it.cookies != null && it.engineVersion != null }

        vm.updateEngine()
        val s = vm.state.first { it.updateMessage != null }
        assertEquals("Engine updated", s.updateMessage)
        assertEquals(2, s.cookies?.count)
        assertEquals("2025.01.01", s.engineVersion)
    }

    @Test
    fun settersPersistThroughAppSettings() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        ) { File(tmp.newFolder(), "s.preferences_pb") }
        val app = AppSettings(store)
        val storage = StorageSettings(store)
        val vm = SettingsViewModel(app, storage, FakeEngine(), cookiesStore = cookiesStore)

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
