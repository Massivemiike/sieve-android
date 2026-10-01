package com.sieve.app.download

import com.sieve.app.ui.download.DownloadPresets
import com.sieve.app.ui.download.DownloadViewModel
import com.sieve.engine.model.VideoInfo
import com.sieve.engine.repo.AnalyzeOutcome
import com.sieve.engine.repo.EngineEvent
import com.sieve.engine.repo.YtDlpEngine
import com.sieve.engine.update.UpdateChannel
import com.sieve.engine.update.UpdateCheck
import com.sieve.engine.update.UpdateResult
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.QueueJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeEngine(var outcome: AnalyzeOutcome) : YtDlpEngine {
        /** The cookies file each analyze call was given. */
        val analyzeCookies = mutableListOf<String?>()
        /** The (proxy, user-agent) each analyze call was given. */
        val analyzeNetwork = mutableListOf<Pair<String?, String?>>()
        override suspend fun analyze(url: String, cookiesBrowser: String?, cookiesFile: String?, proxy: String?, userAgent: String?): AnalyzeOutcome {
            analyzeCookies += cookiesFile
            analyzeNetwork += proxy to userAgent
            return outcome
        }
        override fun download(id: String, url: String, args: List<String>): Flow<EngineEvent> = emptyFlow()
        override fun cancel(id: String): Boolean = true
        override suspend fun version(): String? = "2025.01.01"
        override suspend fun checkUpdate(): UpdateCheck = throw NotImplementedError()
        override suspend fun doUpdate(channel: UpdateChannel): UpdateResult = throw NotImplementedError()
    }

    private val info = VideoInfo(
        id = "abc", title = "My Vid", uploader = "Chan", extractor = "youtube",
        thumbnail = "http://t", duration = 100.0,
    )

    private fun vm(outcome: AnalyzeOutcome, sink: MutableList<QueueJob>) =
        DownloadViewModel(FakeEngine(outcome), { sink += it }, idGen = { "fixed-id" })

    @Test fun analyzeSuccessSetsInfo() = runTest {
        val vm = vm(AnalyzeOutcome.Success(info), mutableListOf())
        vm.onUrlChange("https://youtube.com/watch?v=abc")
        vm.analyze()
        advanceUntilIdle()
        assertEquals(info, vm.state.value.analyzed)
        assertNull(vm.state.value.error)
        assertEquals(false, vm.state.value.analyzing)
    }

    @Test fun analyzeFailureIsHumanized() = runTest {
        val vm = vm(AnalyzeOutcome.Failure("ERROR: HTTP Error 429: Too Many Requests"), mutableListOf())
        vm.onUrlChange("https://x/y")
        vm.analyze()
        advanceUntilIdle()
        assertTrue(vm.state.value.error!!.contains("Rate-limited"))
        assertEquals("Wait a few minutes and retry.", vm.state.value.errorHint)
    }

    @Test fun analyzeFailureStoresMessageAndHintSeparately() = runTest {
        val blob = "WARNING: [youtube] abc: Sign in to confirm your age. Some formats may be missing\n" +
            "ERROR: [youtube] abc: Unable to download API page: HTTP Error 429: Too Many Requests\n"
        val vm = vm(AnalyzeOutcome.Failure(blob), mutableListOf())
        vm.onUrlChange("https://x/y")
        vm.analyze()
        advanceUntilIdle()
        assertEquals("Rate-limited by the site", vm.state.value.error)
        assertEquals("Wait a few minutes and retry.", vm.state.value.errorHint)
        assertEquals(false, vm.state.value.analyzing)
    }

    @Test fun analyzeFailureWithoutHintHasNullHint() = runTest {
        val vm = vm(AnalyzeOutcome.Failure("ERROR: [youtube] abc: Video unavailable"), mutableListOf())
        vm.onUrlChange("https://x/y")
        vm.analyze()
        advanceUntilIdle()
        assertEquals("Not available — it may have been removed or made private", vm.state.value.error)
        assertNull(vm.state.value.errorHint)
    }

    @Test fun editingTheUrlClearsErrorAndHint() = runTest {
        val vm = vm(AnalyzeOutcome.Failure("ERROR: HTTP Error 429: Too Many Requests"), mutableListOf())
        vm.onUrlChange("https://x/y")
        vm.analyze()
        advanceUntilIdle()
        vm.onUrlChange("https://x/z")
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.errorHint)
    }

    @Test fun downloadBuildsJobFromPresetAndInfo() = runTest {
        val sink = mutableListOf<QueueJob>()
        val vm = vm(AnalyzeOutcome.Success(info), sink)
        vm.onUrlChange("https://youtube.com/watch?v=abc")
        vm.analyze(); advanceUntilIdle()
        vm.selectPreset("archive")
        vm.download(); advanceUntilIdle()

        assertEquals(1, sink.size)
        val job = sink.first()
        val spec = job.spec as JobSpec.Download
        assertTrue(spec.engineArgs.contains("-f"))
        assertTrue(spec.engineArgs.contains("bestvideo+bestaudio/best"))
        assertTrue(spec.engineArgs.contains("--embed-subs")) // archive extra args threaded
        assertEquals("%(title).150B [%(id)s].%(ext)s", job.output.outputTemplate)
        assertEquals("My Vid", job.title)
        assertEquals("http://t", job.thumbnailUrl)
        assertEquals("youtube", job.site)
    }

    @Test fun downloadWorksWithoutPriorAnalyze() = runTest {
        val sink = mutableListOf<QueueJob>()
        val vm = vm(AnalyzeOutcome.Failure("x"), sink)
        vm.onUrlChange("https://x/y")
        vm.selectPreset("best-1080")
        vm.download(); advanceUntilIdle()
        assertEquals(1, sink.size)
        assertTrue((sink.first().spec as JobSpec.Download).engineArgs.contains("-f"))
        assertEquals("", sink.first().title) // no analyzed info
    }

    // ---- Windows download defaults: embed metadata + cover art, 4 parallel fragments ----

    private fun argsFor(presetId: String): List<String> {
        val sink = mutableListOf<QueueJob>()
        val vm = DownloadViewModel(FakeEngine(AnalyzeOutcome.Failure("x")), { sink += it }, idGen = { "id" }, initialPresetId = presetId)
        vm.onUrlChange("https://x/y")
        vm.download()
        dispatcher.scheduler.advanceUntilIdle()
        return (sink.single().spec as JobSpec.Download).engineArgs
    }

    @Test fun everyPresetEmbedsMetadataAndCoverArtAndUsesFourFragments() {
        for (p in DownloadPresets.ALL) {
            val args = argsFor(p.id)
            assertEquals(1, args.count { it == "--embed-metadata" }, p.id)
            assertEquals(1, args.count { it == "--embed-thumbnail" }, p.id)
            assertEquals("4", args[args.indexOf("-N") + 1], p.id)
            assertTrue("--convert-thumbnails" !in args, p.id)
        }
    }

    @Test fun anAudioPresetKeepsTheDesktopArgumentOrder() = assertEquals(
        listOf(
            "-f", "bestaudio/best", "-o", "%(title).150B [%(id)s].%(ext)s", "-P", "~/Videos/yt-dlp",
            "-x", "--audio-format", "mp3", "--audio-quality", "0",
            "--embed-metadata", "--embed-thumbnail", "-N", "4",
        ),
        argsFor("audio-mp3"),
    )

    @Test fun theArchivePresetKeepsItsOwnExtrasAndAddsNoDuplicates() {
        val args = argsFor("archive")
        assertTrue(args.containsAll(listOf("--embed-subs", "--all-subs", "--embed-chapters", "--write-info-json", "--remux-video", "mkv")))
        assertEquals(1, args.count { it == "--embed-thumbnail" })
    }

    // ---- Settings network rows reach yt-dlp ----

    private fun networkArgs(
        settings: com.sieve.engine.args.EngineSettings,
        speed: String?,
        presetId: String = "audio-mp3",
    ): List<String> {
        val sink = mutableListOf<QueueJob>()
        val vm = DownloadViewModel(
            FakeEngine(AnalyzeOutcome.Failure("x")), { sink += it }, idGen = { "id" }, initialPresetId = presetId,
            engineSettings = { settings }, speedLimit = { speed },
        )
        vm.onUrlChange("https://x/y")
        vm.download()
        dispatcher.scheduler.advanceUntilIdle()
        return (sink.single().spec as JobSpec.Download).engineArgs
    }

    @Test fun speedLimitProxyUserAgentAndCookiesAllReachTheArgsInDesktopOrder() {
        val args = networkArgs(
            com.sieve.engine.args.EngineSettings(
                concurrentFragments = 4, proxy = "socks5://127.0.0.1:1080", cookiesFile = "/data/user/0/app/files/cookies.txt",
                userAgent = "Mozilla/5.0 Test",
            ),
            speed = "2M",
        )
        // ...extras, toggles, then speed -> fragments -> proxy -> cookies -> user agent (YtdlpArgs.build order)
        val tail = args.subList(args.indexOf("--embed-thumbnail") + 1, args.size)
        assertEquals(
            listOf(
                "--limit-rate", "2M", "-N", "4", "--proxy", "socks5://127.0.0.1:1080",
                "--cookies", "/data/user/0/app/files/cookies.txt", "--user-agent", "Mozilla/5.0 Test",
            ),
            tail,
        )
    }

    @Test fun noSpeedLimitMeansNoLimitRateFlag() {
        assertTrue("--limit-rate" !in networkArgs(defaultEngineSettingsForTest(), speed = null))
    }

    private fun defaultEngineSettingsForTest() =
        com.sieve.engine.args.EngineSettings(concurrentFragments = com.sieve.engine.args.EngineSettings.DEFAULT_CONCURRENT_FRAGMENTS)

    @Test fun analyzeHandsTheEngineTheCookiesFile() = runTest {
        val engine = FakeEngine(AnalyzeOutcome.Failure("x"))
        val vm = DownloadViewModel(
            engine, { }, idGen = { "id" },
            engineSettings = { com.sieve.engine.args.EngineSettings(cookiesFile = "/files/cookies.txt") },
        )
        vm.onUrlChange("https://x/y")
        vm.analyze(); advanceUntilIdle()
        assertEquals(listOf<String?>("/files/cookies.txt"), engine.analyzeCookies)
    }

    @Test fun analyzeSendsNoCookiesFileWhenNoneIsSet() = runTest {
        val engine = FakeEngine(AnalyzeOutcome.Failure("x"))
        val vm = DownloadViewModel(engine, { }, idGen = { "id" })
        vm.onUrlChange("https://x/y")
        vm.analyze(); advanceUntilIdle()
        assertEquals(listOf<String?>(null), engine.analyzeCookies)
    }

    @Test fun analyzeHandsTheEngineTheProxyAndUserAgent() = runTest {
        val engine = FakeEngine(AnalyzeOutcome.Failure("x"))
        val vm = DownloadViewModel(
            engine, { }, idGen = { "id" },
            engineSettings = { com.sieve.engine.args.EngineSettings(proxy = "socks5://127.0.0.1:1080", userAgent = "SieveTest/1.0") },
        )
        vm.onUrlChange("https://x/y")
        vm.analyze(); advanceUntilIdle()
        assertEquals(listOf<Pair<String?, String?>>("socks5://127.0.0.1:1080" to "SieveTest/1.0"), engine.analyzeNetwork)
    }

    @Test fun analyzeSendsNoProxyOrUserAgentWhenNoneIsSet() = runTest {
        val engine = FakeEngine(AnalyzeOutcome.Failure("x"))
        val vm = DownloadViewModel(engine, { }, idGen = { "id" })
        vm.onUrlChange("https://x/y")
        vm.analyze(); advanceUntilIdle()
        assertEquals(listOf<Pair<String?, String?>>(null to null), engine.analyzeNetwork)
    }

    // ---- default / last-used preset ----

    private fun presetVm(start: String, remembered: MutableList<String> = mutableListOf()) = DownloadViewModel(
        FakeEngine(AnalyzeOutcome.Failure("x")), { }, idGen = { "id" },
        initialPresetId = start, rememberPreset = { remembered += it },
    )

    @Test fun startsOnTheSavedPreset() {
        assertEquals("best-720", presetVm("best-720").state.value.selectedPresetId)
    }

    @Test fun anUnknownSavedPresetFallsBackToTheFirstOne() {
        assertEquals(DownloadPresets.DEFAULT_ID, presetVm("no-such-preset").state.value.selectedPresetId)
    }

    @Test fun withNothingSavedItStartsOnBestVideo() {
        val vm = DownloadViewModel(FakeEngine(AnalyzeOutcome.Failure("x")), { }, idGen = { "id" })
        assertEquals("best-video", vm.state.value.selectedPresetId)
    }

    @Test fun pickingAPresetRemembersItAsTheNextStart() = runTest {
        val remembered = mutableListOf<String>()
        val vm = presetVm("best-video", remembered)
        vm.selectPreset("audio-mp3")
        advanceUntilIdle()
        assertEquals("audio-mp3", vm.state.value.selectedPresetId)
        assertEquals(listOf("audio-mp3"), remembered)
    }

    @Test fun anUnknownPresetIdIsIgnoredAndNotRemembered() = runTest {
        val remembered = mutableListOf<String>()
        val vm = presetVm("best-1080", remembered)
        vm.selectPreset("nope")
        advanceUntilIdle()
        assertEquals("best-1080", vm.state.value.selectedPresetId)
        assertTrue(remembered.isEmpty())
    }

    @Test fun clearingKeepsTheChosenPreset() = runTest {
        val vm = presetVm("best-video")
        vm.selectPreset("best-720")
        vm.onUrlChange("https://x/y")
        vm.clear()
        assertEquals("best-720", vm.state.value.selectedPresetId)
        assertEquals("", vm.state.value.url)
    }

    @Test fun downloadUsesTheStartingPresetWithoutAnyTap() = runTest {
        val sink = mutableListOf<QueueJob>()
        val vm = DownloadViewModel(FakeEngine(AnalyzeOutcome.Failure("x")), { sink += it }, idGen = { "id" }, initialPresetId = "audio-mp3")
        vm.onUrlChange("https://x/y")
        vm.download(); advanceUntilIdle()
        val args = (sink.single().spec as JobSpec.Download).engineArgs
        assertTrue(args.containsAll(listOf("-x", "--audio-format", "mp3")))
        assertEquals("MP3 320kbps", sink.single().format)
    }

    @Test fun mp4PresetsAskForH264FirstAndKeepTheirFallbacks() {
        // [ext=mp4] alone also matches AV1-in-MP4 (YouTube serves it first), which breaks the
        // "H.264, widely compatible" promise; the old chain stays behind it as the fallback.
        for ((id, h) in listOf("best-1080" to 1080, "best-720" to 720)) {
            val alternatives = DownloadPresets.byId(id).format.split('/')
            assertEquals("bestvideo[height<=$h][vcodec^=avc1]+bestaudio[ext=m4a]", alternatives.first())
            assertEquals("best", alternatives.last())
            assertTrue(alternatives.contains("bestvideo[height<=$h][ext=mp4]+bestaudio[ext=m4a]"))
        }
    }
}
