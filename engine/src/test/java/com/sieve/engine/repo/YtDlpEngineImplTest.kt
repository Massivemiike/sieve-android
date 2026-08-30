package com.sieve.engine.repo

import com.sieve.engine.update.GithubReleaseApi
import com.sieve.engine.update.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeGithub(private val tag: String? = null) : GithubReleaseApi {
    override suspend fun latestTag(): String? = tag
}

private class FakeClient(
    private val ver: String? = null,
    private val execResults: ArrayDeque<Result<ExecResult>> = ArrayDeque(),
    private val execLines: List<String> = emptyList(),
    private val updateStatus: String = "DONE",
    private val onExecute: (() -> Unit)? = null,
) : YoutubeDLClient {
    override fun version(): String? = ver
    override fun execute(
        processId: String,
        url: String,
        options: List<String>,
        onProgress: (Float, Long, String) -> Unit,
    ): ExecResult {
        execLines.forEach { onProgress(0f, 0L, it) }
        onExecute?.invoke()
        val r = if (execResults.isNotEmpty()) execResults.removeFirst() else Result.success(ExecResult(0, "", ""))
        return r.getOrThrow()
    }
    override fun destroy(processId: String): Boolean = true
    override fun update(nightly: Boolean): String = updateStatus
}

class YtDlpEngineImplAnalyzeTest {
    private val storyboardJson = """{"id":"x","formats":[{"format_id":"sb0","format_note":"storyboard"}]}"""
    private val realJson = """{"id":"x","title":"T","formats":[{"format_id":"22","vcodec":"avc1","acodec":"mp4a"}]}"""

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
            execResults = ArrayDeque(listOf(err("ERROR: A"), err("ERROR: B"))),
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
        val client = FakeClient(execResults = ArrayDeque(listOf(ok(storyboardJson), err("ERROR: nope"))))
        val r = YtDlpEngineImpl(client, FakeGithub(), io = Dispatchers.Unconfined).analyze("u", "chrome")
        assertTrue(r is AnalyzeOutcome.Success)
        assertFalse((r as AnalyzeOutcome.Success).info.cookieFallback)
    }

    private fun ok(out: String) = Result.success(ExecResult(0, out, ""))
    private fun err(msg: String) = Result.failure<ExecResult>(RuntimeException(msg))
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
    @Test fun downloadEmitsProgressThenCompleted() = runTest {
        val client = FakeClient(
            execLines = listOf("[download]  50.0% of 10.00MiB at 1.00MiB/s ETA 00:05", "[download] done"),
            execResults = ArrayDeque(listOf(Result.success(ExecResult(0, "", "")))),
        )
        val events = YtDlpEngineImpl(client, FakeGithub(), io = Dispatchers.Unconfined)
            .download("id1", "u", listOf("-f", "best")).toList()
        assertTrue(events.any { it is EngineEvent.Progress })
        val last = events.last()
        assertTrue(last is EngineEvent.Completed && last.exitCode == 0)
    }

    // C1: a download killed by cancel(id) emits Cancelled, not Completed/error.
    @Test fun cancelledDownloadEmitsCancelled() = runTest {
        lateinit var engine: YtDlpEngineImpl
        val client = FakeClient(
            execResults = ArrayDeque(listOf(Result.failure<ExecResult>(RuntimeException("Command was canceled")))),
            onExecute = { engine.cancel("id1") },
        )
        engine = YtDlpEngineImpl(client, FakeGithub(), io = Dispatchers.Unconfined)
        val events = engine.download("id1", "u", listOf("-f", "best")).toList()
        assertTrue(events.any { it is EngineEvent.Cancelled })
        assertFalse(events.any { it is EngineEvent.Completed })
    }
}
