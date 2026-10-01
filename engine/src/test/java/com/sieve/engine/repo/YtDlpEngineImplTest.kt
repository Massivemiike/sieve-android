package com.sieve.engine.repo

import com.sieve.engine.update.GithubReleaseApi
import com.sieve.engine.update.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeGithub(private val tag: String? = null) : GithubReleaseApi {
    override suspend fun latestTag(): String? = tag
}

/** One recorded `client.execute` call. */
private data class Call(val id: String, val url: String, val options: List<String>)

private class FakeClient(
    private val ver: String? = null,
    private val execResults: ArrayDeque<Result<ExecResult>> = ArrayDeque(),
    private val execLines: List<String> = emptyList(),
    private val updateStatus: String = "DONE",
    private val onExecute: (() -> Unit)? = null,
    /** Block inside execute() until destroy() is called (simulates a hung yt-dlp). */
    private val blockUntilDestroyed: Boolean = false,
) : YoutubeDLClient {
    val calls = CopyOnWriteArrayList<Call>()
    val destroyed = CopyOnWriteArrayList<String>()
    private val killed = CountDownLatch(1)

    override fun version(): String? = ver
    override fun execute(
        processId: String,
        url: String,
        options: List<String>,
        onProgress: (Float, Long, String) -> Unit,
    ): ExecResult {
        calls.add(Call(processId, url, options))
        execLines.forEach { onProgress(0f, 0L, it) }
        onExecute?.invoke()
        if (blockUntilDestroyed) {
            killed.await(10, TimeUnit.SECONDS)
            throw RuntimeException("Command was canceled")
        }
        val r = if (execResults.isNotEmpty()) execResults.removeFirst() else Result.success(ExecResult(0, "", ""))
        return r.getOrThrow()
    }
    override fun destroy(processId: String): Boolean {
        destroyed.add(processId)
        killed.countDown()
        return true
    }
    override fun update(nightly: Boolean): String = updateStatus
}

private const val VIMEO_PAGE = "https://vimeo.com/98044508"
private const val VIMEO_PLAYER = "https://player.vimeo.com/video/98044508"
private const val VIMEO_WALL = "ERROR: [vimeo] 98044508: The web client only works when logged-in. Use --cookies-from-browser or --cookies for the authentication."
private const val EMBED_ERR = "ERROR: Postprocessing: Supported filetypes for thumbnail embedding are: mp3, mkv/mka, ogg/opus/flac, m4a/mp4/m4v/mov"
private const val DRM_ERR = "WARNING: noise\nERROR: [vimeo] 98044508: This video is DRM protected"

private fun ok(out: String, err: String = "") = Result.success(ExecResult(0, out, err))
private fun threw(msg: String) = Result.failure<ExecResult>(RuntimeException(msg))
private fun exited(code: Int, err: String) = Result.success(ExecResult(code, "", err))

class YtDlpEngineImplAnalyzeTest {
    private val storyboardJson = """{"id":"x","formats":[{"format_id":"sb0","format_note":"storyboard"}]}"""
    private val realJson = """{"id":"x","title":"T","formats":[{"format_id":"22","vcodec":"avc1","acodec":"mp4a"}]}"""

    private fun newEngine(client: FakeClient, timeoutMs: Long = 150_000) =
        YtDlpEngineImpl(client, FakeGithub(), io = Dispatchers.Unconfined, analyzeTimeoutMs = timeoutMs)

    @Test fun cookieStoryboardFallbackFlagsResult() = runTest {
        val client = FakeClient(
            execResults = ArrayDeque(listOf(ok(storyboardJson), ok(realJson))),
        )
        val r = YtDlpEngineImpl(client, FakeGithub(), io = Dispatchers.Unconfined).analyze("u", "chrome")
        assertTrue(r is AnalyzeOutcome.Success)
        assertTrue((r as AnalyzeOutcome.Success).info.cookieFallback)
    }

    @Test fun originalErrorWhenBothThrow() = runTest {
        val client = FakeClient(
            execResults = ArrayDeque(listOf(threw("ERROR: A"), threw("ERROR: B"))),
        )
        val r = YtDlpEngineImpl(client, FakeGithub(), io = Dispatchers.Unconfined).analyze("u", "chrome")
        assertEquals("ERROR: A", (r as AnalyzeOutcome.Failure).message)
    }

    // C2: without cookies, a storyboard-only result is returned as SUCCESS (desktop never
    // re-checks storyboards when no cookies were used).
    @Test fun noCookiesStoryboardIsSuccess() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(ok(storyboardJson))))
        val r = YtDlpEngineImpl(client, FakeGithub(), io = Dispatchers.Unconfined).analyze("u", null)
        assertTrue(r is AnalyzeOutcome.Success)
        assertFalse((r as AnalyzeOutcome.Success).info.cookieFallback)
    }

    // C2: cookies + storyboard-only + fallback also fails → keep the ORIGINAL as success.
    @Test fun storyboardWithFailedFallbackKeepsOriginal() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(ok(storyboardJson), threw("ERROR: nope"))))
        val r = YtDlpEngineImpl(client, FakeGithub(), io = Dispatchers.Unconfined).analyze("u", "chrome")
        assertTrue(r is AnalyzeOutcome.Success)
        assertFalse((r as AnalyzeOutcome.Success).info.cookieFallback)
    }

    // ---- cookies.txt: anonymous first, the file only when the site asks for a login ----
    private val signIn = "ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate for some users."

    @Test fun cookiesFileIsNeverSentWhenTheAnonymousAnalyzeWorks() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(ok(realJson))))
        val r = newEngine(client).analyze("https://example.com/v", null, "/data/cookies.txt")
        assertTrue(r is AnalyzeOutcome.Success)
        assertEquals(1, client.calls.size)
        assertFalse("--cookies" in client.calls.single().options)
    }

    @Test fun aLoginWallRetriesOnceWithTheCookiesFile() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(exited(1, signIn), ok(realJson))))
        val r = newEngine(client).analyze("https://example.com/v", null, "/data/cookies.txt")
        assertTrue(r is AnalyzeOutcome.Success)
        assertEquals(2, client.calls.size)
        assertFalse("--cookies" in client.calls[0].options)
        val retry = client.calls[1].options
        assertEquals("/data/cookies.txt", retry[retry.indexOf("--cookies") + 1])
    }

    @Test fun ifTheCookiesAlsoFailTheOriginalErrorWins() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(exited(1, signIn), exited(1, "ERROR: cookies are stale"))))
        val r = newEngine(client).analyze("https://example.com/v", null, "/data/cookies.txt")
        assertTrue(r is AnalyzeOutcome.Failure)
        assertTrue((r as AnalyzeOutcome.Failure).message.contains("Sign in"))
        assertEquals(2, client.calls.size)
    }

    @Test fun aFailureThatIsNotALoginIsNotRetriedWithCookies() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(exited(1, "ERROR: [generic] Unsupported URL: https://example.com/v"))))
        val r = newEngine(client).analyze("https://example.com/v", null, "/data/cookies.txt")
        assertTrue(r is AnalyzeOutcome.Failure)
        assertEquals(1, client.calls.size)
    }

    @Test fun noCookiesFileMeansALoginWallIsJustAFailure() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(exited(1, signIn))))
        val r = newEngine(client).analyze("https://example.com/v", null, null)
        assertTrue(r is AnalyzeOutcome.Failure)
        assertEquals(1, client.calls.size)
    }

    @Test fun aBlankCookiesFileIsTreatedAsNone() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(exited(1, signIn))))
        newEngine(client).analyze("https://example.com/v", null, "  ")
        assertEquals(1, client.calls.size)
    }

    @Test fun analyzeOptionsAreFlatCappedUtf8AndKeepWarnings() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(ok(realJson))))
        newEngine(client).analyze("https://example.com/v", null)
        val opts = client.calls.single().options
        assertFalse("--no-warnings" in opts)
        assertTrue("-J" in opts)
        assertTrue("--flat-playlist" in opts)
        assertEquals("1:1000", opts[opts.indexOf("-I") + 1])
        assertEquals("utf-8", opts[opts.indexOf("--encoding") + 1])
        // The library injects these itself; adding them would re-trigger the ffmpeg hang.
        assertFalse("--js-runtimes" in opts)
        assertFalse("--ffmpeg-location" in opts)
    }

    @Test fun eachAnalyzeCallGetsAUniqueProcessId() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(ok(realJson), ok(realJson))))
        val e = newEngine(client)
        e.analyze("https://example.com/a", null)
        e.analyze("https://example.com/b", null)
        val ids = client.calls.map { it.id }
        assertEquals(2, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("analyze-") })
    }

    @Test fun analyzeNormalizesLinkedinCountrySubdomain() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(ok(realJson))))
        newEngine(client).analyze("  https://uk.linkedin.com/posts/x_y-activity-715124157037194854-4Gu7 ", null)
        assertEquals("https://www.linkedin.com/posts/x_y-activity-715124157037194854-4Gu7", client.calls.single().url)
    }

    @Test fun vimeoWebClientErrorRetriesWithPlayerUrl() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(threw(VIMEO_WALL), ok(realJson))))
        val r = newEngine(client).analyze(VIMEO_PAGE, null)
        assertTrue(r is AnalyzeOutcome.Success)
        assertEquals(listOf(VIMEO_PAGE, VIMEO_PLAYER), client.calls.map { it.url })
        assertEquals(2, client.calls.map { it.id }.toSet().size)
    }

    @Test fun vimeoFallbackAlsoWorksForANonThrownExit() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(exited(1, "WARNING: x\n$VIMEO_WALL"), ok(realJson))))
        val r = newEngine(client).analyze(VIMEO_PAGE, null)
        assertTrue(r is AnalyzeOutcome.Success)
        assertEquals(VIMEO_PLAYER, client.calls.last().url)
    }

    @Test fun fallbackIsTriedOnceAndItsErrorWins() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(threw(VIMEO_WALL), threw("ERROR: [vimeo] 98044508: Private video"))))
        val r = newEngine(client).analyze(VIMEO_PAGE, null)
        assertEquals("ERROR: [vimeo] 98044508: Private video", (r as AnalyzeOutcome.Failure).message)
        assertEquals(2, client.calls.size)
    }

    @Test fun unrelatedErrorDoesNotRetry() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(threw("ERROR: Unsupported URL: $VIMEO_PAGE"))))
        val r = newEngine(client).analyze(VIMEO_PAGE, null)
        assertTrue(r is AnalyzeOutcome.Failure)
        assertEquals(1, client.calls.size)
    }

    @Test fun firstFiveWarningLinesAreKept() = runTest {
        val stderr = (1..7).joinToString("\n") { "WARNING: w$it" } + "\nERROR: not a warning\n[info] WARNING: nope"
        val client = FakeClient(execResults = ArrayDeque(listOf(ok(realJson, stderr))))
        val r = newEngine(client).analyze("https://example.com/v", null) as AnalyzeOutcome.Success
        assertEquals((1..5).map { "WARNING: w$it" }, r.info.warnings)
    }

    @Test fun noWarningsMeansEmptyList() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(ok(realJson))))
        val r = newEngine(client).analyze("https://example.com/v", null) as AnalyzeOutcome.Success
        assertTrue(r.info.warnings.isEmpty())
    }

    @Test fun emptyPlaylistIsAFailure() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(ok("""{"_type":"playlist","id":"p","title":"P","entries":[]}"""))))
        val r = newEngine(client).analyze("https://example.com/list", null)
        assertEquals("This link has no downloadable videos.", (r as AnalyzeOutcome.Failure).message)
    }

    @Test fun flatPlaylistWithEntriesIsASuccess() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(ok("""{"_type":"playlist","id":"p","title":"P","entries":[{"id":"a"},{"id":"b"}]}"""))))
        val r = newEngine(client).analyze("https://example.com/list", null)
        assertTrue(r is AnalyzeOutcome.Success && r.info.isPlaylist && r.info.entries.size == 2)
    }

    @Test fun hungAnalyzeIsKilledAndReportsATimeout() = runTest {
        val client = FakeClient(blockUntilDestroyed = true)
        val r = newEngine(client, timeoutMs = 50).analyze("https://example.com/slow", null)
        assertEquals(
            "Timed out while reading this link — the site may be slow or blocking requests.",
            (r as AnalyzeOutcome.Failure).message,
        )
        assertEquals(client.calls.map { it.id }, client.destroyed.toList())
    }

    @Test fun analyzeThatFinishesInTimeIsNeverKilled() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(ok(realJson))))
        newEngine(client, timeoutMs = 60_000).analyze("https://example.com/v", null)
        assertTrue(client.destroyed.isEmpty())
    }

    @Test fun downloadStartsFromTheUrlFormAnalyzeSettledOn() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(threw(VIMEO_WALL), ok(realJson), ok("", ""))))
        val e = newEngine(client)
        e.analyze(VIMEO_PAGE, null)
        val events = e.download("id1", VIMEO_PAGE, listOf("-f", "best")).toList()
        assertEquals(VIMEO_PLAYER, client.calls.last().url)
        assertFalse(events.any { it is EngineEvent.Log && it.line.startsWith("[retry]") })
    }
}

class YtDlpEngineImplUpdateTest {
    @Test fun checkUpdateUsesGithub() = runTest {
        val engine = YtDlpEngineImpl(FakeClient(ver = "2025.07.01"), FakeGithub(tag = "2025.08.20"), io = Dispatchers.Unconfined)
        val c = engine.checkUpdate()
        assertTrue(c.updateAvailable)
        assertEquals("2025.08.20", c.latest)
        assertEquals("2025.07.01", c.current)
    }

    @Test fun doUpdateMapsStatus() = runTest {
        val engine = YtDlpEngineImpl(FakeClient(updateStatus = "ALREADY_UP_TO_DATE"), FakeGithub(), io = Dispatchers.Unconfined)
        assertEquals(UpdateResult(true, "ALREADY_UP_TO_DATE"), engine.doUpdate())
    }
}

class YtDlpEngineImplDownloadTest {
    private val args = listOf("-f", "best")

    private fun newEngine(client: FakeClient) = YtDlpEngineImpl(client, FakeGithub(), io = Dispatchers.Unconfined)

    @Test fun downloadEmitsProgressThenCompleted() = runTest {
        val client = FakeClient(
            execLines = listOf("[download]  50.0% of 10.00MiB at 1.00MiB/s ETA 00:05", "[download] done"),
            execResults = ArrayDeque(listOf(Result.success(ExecResult(0, "", "")))),
        )
        val events = newEngine(client).download("id1", "u", args).toList()
        assertTrue(events.any { it is EngineEvent.Progress })
        val last = events.last()
        assertTrue(last is EngineEvent.Completed && last.exitCode == 0)
    }

    // C1: a download killed by cancel(id) emits Cancelled, not Completed/error.
    @Test fun cancelledDownloadEmitsCancelled() = runTest {
        lateinit var engine: YtDlpEngineImpl
        val client = FakeClient(
            execResults = ArrayDeque(listOf(threw("Command was canceled"))),
            onExecute = { engine.cancel("id1") },
        )
        engine = newEngine(client)
        val events = engine.download("id1", "u", args).toList()
        assertTrue(events.any { it is EngineEvent.Cancelled })
        assertFalse(events.any { it is EngineEvent.Completed })
    }

    // Desktop CLAUDE.md: "Must always pass --no-warnings to downloads (analyze keeps warnings)". It keeps WARNING
    // lines out of the failure text the retry verdict and the humanizer read.
    @Test fun encodingAndNoWarningsFlagsArePrependedAndTheRestOfTheArgsAreUntouched() = runTest {
        val client = FakeClient()
        newEngine(client).download("id1", "u", args).toList()
        val opts = client.calls.single().options
        assertEquals(listOf("--encoding", "utf-8", "--no-warnings") + args, opts)
    }

    @Test fun noWarningsSurvivesEveryRecoveryRetry() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(threw(DRM_ERR), threw(EMBED_ERR), Result.success(ExecResult(0, "", "")))))
        newEngine(client).download("id1", "https://example.com/v", args + "--embed-thumbnail").toList()
        assertEquals(3, client.calls.size)
        assertTrue(client.calls.all { it.options.count { o -> o == "--no-warnings" } == 1 })
    }

    @Test fun downloadNormalizesLinkedinUrl() = runTest {
        val client = FakeClient()
        newEngine(client).download("id1", "https://www.linkedin.com/embed/feed/update/urn:li:activity:715", args).toList()
        assertEquals("https://www.linkedin.com/feed/update/urn:li:activity:715", client.calls.single().url)
    }

    @Test fun vimeoWallRetriesOnceWithThePlayerUrl() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(threw(VIMEO_WALL), Result.success(ExecResult(0, "", "")))))
        val events = newEngine(client).download("id1", VIMEO_PAGE, args).toList()
        assertEquals(listOf(VIMEO_PAGE, VIMEO_PLAYER), client.calls.map { it.url })
        assertTrue(client.calls.all { it.id == "id1" })
        assertTrue(events.contains(EngineEvent.Log("[retry] Retrying with the video player URL", null, false)))
        assertEquals(EngineEvent.Completed(0), events.last())
        assertFalse(events.any { it is EngineEvent.Log && it.isError })
    }

    @Test fun nonThrownExitIsRetriedTheSameWay() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(exited(1, VIMEO_WALL), Result.success(ExecResult(0, "", "")))))
        val events = newEngine(client).download("id1", VIMEO_PAGE, args).toList()
        assertEquals(VIMEO_PLAYER, client.calls.last().url)
        assertEquals(EngineEvent.Completed(0), events.last())
    }

    @Test fun drmRetriesOnceWithCheckFormats() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(threw(DRM_ERR), threw(DRM_ERR))))
        val events = newEngine(client).download("id1", "https://example.com/v", args).toList()
        assertEquals(2, client.calls.size)
        assertFalse("--check-formats" in client.calls[0].options)
        assertEquals(1, client.calls[1].options.count { it == "--check-formats" })
        val retryLogs = events.filterIsInstance<EngineEvent.Log>().filter { it.line.startsWith("[retry]") }
        assertEquals(listOf("[retry] That format is DRM-locked — retrying with a playable one"), retryLogs.map { it.line })
        // Final failure: the LAST attempt's error log, then Completed(1) — once.
        val tail = events.takeLast(2)
        assertEquals(EngineEvent.Log(DRM_ERR, null, true), tail[0])
        assertEquals(EngineEvent.Completed(1), tail[1])
        assertEquals(1, events.count { it is EngineEvent.Completed })
    }

    @Test fun drmOnlyMediaReportsTheDrmErrorNotTheEmptyFormatList() = runTest {
        // --check-formats drops every DRM format; yt-dlp then says "Requested format is not available",
        // which would read as a preset problem. The DRM error is the real cause.
        val noFormats = "ERROR: [vimeo] 76979871: Requested format is not available. Use --list-formats for a list of available formats"
        val client = FakeClient(execResults = ArrayDeque(listOf(threw(DRM_ERR), threw(noFormats))))
        val events = newEngine(client).download("id1", "https://example.com/v", args).toList()
        assertEquals(2, client.calls.size)
        assertEquals(listOf<EngineEvent>(EngineEvent.Log(DRM_ERR, null, true), EngineEvent.Completed(1)), events.takeLast(2))
    }

    @Test fun drmOnlyMediaNonThrownPathAlsoReportsTheDrmError() = runTest {
        val noFormats = "ERROR: [vimeo] 76979871: Requested format is not available."
        val client = FakeClient(execResults = ArrayDeque(listOf(exited(1, DRM_ERR), exited(1, noFormats))))
        val events = newEngine(client).download("id1", "https://example.com/v", args).toList()
        assertEquals(listOf<EngineEvent>(EngineEvent.Log(DRM_ERR, null, true), EngineEvent.Completed(1)), events.takeLast(2))
    }

    @Test fun drmRetrySucceeds() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(threw(DRM_ERR), Result.success(ExecResult(0, "", "")))))
        val events = newEngine(client).download("id1", "https://example.com/v", args).toList()
        assertEquals(EngineEvent.Completed(0), events.last())
        assertFalse(events.any { it is EngineEvent.Log && it.isError })
    }

    // webm (AV1/VP9 + Opus) can't carry a cover: the media is saved, then yt-dlp fails the run on the embed.
    @Test fun aThumbnailEmbedFailureRetriesOnceWithoutTheEmbed() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(exited(1, EMBED_ERR), Result.success(ExecResult(0, "", "")))))
        val events = newEngine(client).download("id1", "https://example.com/v", args + listOf("--embed-metadata", "--embed-thumbnail")).toList()
        assertEquals(2, client.calls.size)
        assertEquals(1, client.calls[0].options.count { it == "--embed-thumbnail" })
        assertFalse("--embed-thumbnail" in client.calls[1].options)
        assertTrue("--embed-metadata" in client.calls[1].options) // only the cover is dropped
        assertTrue(events.any { it is EngineEvent.Log && it.line.startsWith("[retry] This format can't carry a cover image") })
        assertEquals(EngineEvent.Completed(0), events.last())
        assertFalse(events.any { it is EngineEvent.Log && it.isError })
    }

    @Test fun theThrownPathAlsoRetriesWithoutTheEmbed() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(threw(EMBED_ERR), Result.success(ExecResult(0, "", "")))))
        val events = newEngine(client).download("id1", "https://example.com/v", args + "--embed-thumbnail").toList()
        assertEquals(2, client.calls.size)
        assertEquals(EngineEvent.Completed(0), events.last())
    }

    @Test fun theEmbedRetryRunsOnceAndThenReportsTheFailure() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(exited(1, EMBED_ERR), exited(1, EMBED_ERR))))
        val events = newEngine(client).download("id1", "https://example.com/v", args + "--embed-thumbnail").toList()
        assertEquals(2, client.calls.size)
        assertEquals(listOf<EngineEvent>(EngineEvent.Log(EMBED_ERR, null, true), EngineEvent.Completed(1)), events.takeLast(2))
    }

    @Test fun noEmbedRetryWhenTheCoverWasNeverRequested() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(exited(1, EMBED_ERR))))
        val events = newEngine(client).download("id1", "https://example.com/v", args).toList()
        assertEquals(1, client.calls.size)
        assertEquals(EngineEvent.Completed(1), events.last())
    }

    @Test fun otherPostprocessingFailuresDoNotTriggerTheEmbedRetry() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(exited(1, "ERROR: Postprocessing: Conversion failed!"))))
        val events = newEngine(client).download("id1", "https://example.com/v", args + "--embed-thumbnail").toList()
        assertEquals(1, client.calls.size)
        assertEquals(EngineEvent.Completed(1), events.last())
    }

    @Test fun drmDoesNotRetryWhenCheckFormatsIsAlreadyPresent() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(threw(DRM_ERR))))
        val events = newEngine(client).download("id1", "https://example.com/v", args + "--check-formats").toList()
        assertEquals(1, client.calls.size)
        assertEquals(EngineEvent.Completed(1), events.last())
    }

    @Test fun altUrlThenDrmEachRunOnce() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(threw(VIMEO_WALL), threw(DRM_ERR), threw(DRM_ERR))))
        val events = newEngine(client).download("id1", VIMEO_PAGE, args).toList()
        assertEquals(listOf(VIMEO_PAGE, VIMEO_PLAYER, VIMEO_PLAYER), client.calls.map { it.url })
        assertTrue("--check-formats" in client.calls[2].options)
        assertEquals(2, events.count { it is EngineEvent.Log && it.line.startsWith("[retry]") })
        assertEquals(listOf<EngineEvent>(EngineEvent.Log(DRM_ERR, null, true), EngineEvent.Completed(1)), events.takeLast(2))
    }

    @Test fun otherFailureEmitsErrorLogAndCompleted1WithoutRetry() = runTest {
        val client = FakeClient(execResults = ArrayDeque(listOf(threw("ERROR: [youtube] abc: Video unavailable"))))
        val events = newEngine(client).download("id1", "https://example.com/v", args).toList()
        assertEquals(1, client.calls.size)
        assertEquals(
            listOf<EngineEvent>(EngineEvent.Log("ERROR: [youtube] abc: Video unavailable", null, true), EngineEvent.Completed(1)),
            events,
        )
    }

    @Test fun cancelledDownloadNeverRetries() = runTest {
        lateinit var engine: YtDlpEngineImpl
        val client = FakeClient(
            execResults = ArrayDeque(listOf(threw(VIMEO_WALL), Result.success(ExecResult(0, "", "")))),
            onExecute = { engine.cancel("id1") },
        )
        engine = newEngine(client)
        val events = engine.download("id1", VIMEO_PAGE, args).toList()
        assertEquals(1, client.calls.size)
        assertTrue(events.any { it is EngineEvent.Cancelled })
        assertFalse(events.any { it is EngineEvent.Completed })
        assertFalse(events.any { it is EngineEvent.Log && it.line.startsWith("[retry]") })
    }

    @Test fun nonThrownFailureAfterCancelDoesNotRetry() = runTest {
        lateinit var engine: YtDlpEngineImpl
        val client = FakeClient(
            execResults = ArrayDeque(listOf(exited(1, DRM_ERR), Result.success(ExecResult(0, "", "")))),
            onExecute = { engine.cancel("id1") },
        )
        engine = newEngine(client)
        val events = engine.download("id1", "https://example.com/v", args).toList()
        assertEquals(1, client.calls.size)
        assertFalse(events.any { it is EngineEvent.Log && it.line.startsWith("[retry]") })
    }
}
