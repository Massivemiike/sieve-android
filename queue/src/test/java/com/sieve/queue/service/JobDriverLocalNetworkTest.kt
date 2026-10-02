package com.sieve.queue.service

import app.cash.turbine.test
import com.sieve.engine.parse.ErrorKind
import com.sieve.engine.parse.YtdlpErrors
import com.sieve.engine.repo.EngineEvent
import com.sieve.queue.core.JobKind
import com.sieve.queue.core.JobSignal
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.Outcome
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.RetryClass
import com.sieve.queue.core.RetryClassifier
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Android 17 drops a download's connections to the local network until ACCESS_LOCAL_NETWORK is granted, and yt-dlp only sees a
 * timeout. When the app says that block is on ([LocalNetworkGuard]), the driver reports the cause once and the retry rules keep out.
 */
class JobDriverLocalNetworkTest {
    private val timeout = "ERROR: [generic] Unable to download webpage: <urlopen error timed out>"

    private fun dl(url: String = "http://nas:8096/v.mp4", args: List<String> = listOf("-f", "best")) =
        QueueJob("a", JobSpec.Download(url, args), OutputRequest("d", "o"))

    private fun failedRun(blob: String, exit: Int = 1) = FakeDownloadPort { flow { emit(EngineEvent.Log(blob, null, true)); emit(EngineEvent.Completed(exit)) } }

    private suspend fun terminalOf(driver: JobDriver, job: QueueJob = dl()): Outcome {
        var outcome: Outcome? = null
        driver.drive(job) { null }.test {
            while (outcome == null) {
                val item = awaitItem()
                if (item is JobSignal.Terminal) outcome = item.outcome
            }
            awaitComplete()
        }
        return outcome!!
    }

    @Test fun `a blocked timeout fails once with the local-network cause, which is never retried`() = runTest {
        val seen = mutableListOf<Pair<String, List<String>>>()
        val guard = LocalNetworkGuard { url, args -> seen += url to args; true }
        val outcome = terminalOf(JobDriver(failedRun(timeout), FakeTranscodePort(), guard), dl("http://nas:8096/v.mp4", listOf("-N", "4")))

        val info = (outcome as Outcome.Failed).info
        assertEquals(YtdlpErrors.localNetworkBlocked("nas"), info.message)
        assertEquals(ErrorKind.LOCAL_NETWORK, YtdlpErrors.humanize(info.message).kind)
        assertEquals("the original yt-dlp output stays for the diagnostics", timeout, info.stderrTail)
        assertEquals(1, info.exitCode)
        assertEquals(RetryClass.PERMANENT, RetryClassifier.classify(info, JobKind.DOWNLOAD))
        assertEquals(listOf("http://nas:8096/v.mp4" to listOf("-N", "4")), seen)
    }

    @Test fun `the same timeout without the block is still the transient network failure it was`() = runTest {
        val outcome = terminalOf(JobDriver(failedRun(timeout), FakeTranscodePort(), LocalNetworkGuard { _, _ -> false }))
        val info = (outcome as Outcome.Failed).info
        assertEquals(timeout, info.message)
        assertEquals(RetryClass.TRANSIENT, RetryClassifier.classify(info, JobKind.DOWNLOAD))
    }

    @Test fun `no guard means no restriction`() = runTest {
        val info = ((terminalOf(JobDriver(failedRun(timeout), FakeTranscodePort()))) as Outcome.Failed).info
        assertEquals(timeout, info.message)
    }

    @Test fun `a failure the site answered is not blamed on the local network`() = runTest {
        for (blob in listOf(
            "ERROR: unable to download video data: HTTP Error 403: Forbidden",
            "ERROR: [generic] Private video. Sign in if you've been granted access",
            "ERROR: unable to write data: [Errno 28] No space left on device",
        )) {
            val info = (terminalOf(JobDriver(failedRun(blob), FakeTranscodePort(), LocalNetworkGuard { _, _ -> true })) as Outcome.Failed).info
            assertEquals(blob, info.message)
        }
    }

    @Test fun `text the rule table does not know counts as a dropped connection when the block is on`() = runTest {
        val info = (terminalOf(JobDriver(failedRun("ERROR: [generic] something odd"), FakeTranscodePort(), LocalNetworkGuard { _, _ -> true })) as Outcome.Failed).info
        assertEquals(ErrorKind.LOCAL_NETWORK, YtdlpErrors.humanize(info.message).kind)
    }

    @Test fun `an engine-level failure is reported the same way`() = runTest {
        val port = FakeDownloadPort { flow { emit(EngineEvent.Failed("java.net.SocketTimeoutException: connect timed out")) } }
        val info = (terminalOf(JobDriver(port, FakeTranscodePort(), LocalNetworkGuard { _, _ -> true })) as Outcome.Failed).info
        assertEquals(YtdlpErrors.localNetworkBlocked("nas"), info.message)
        assertEquals("java.net.SocketTimeoutException: connect timed out", info.stderrTail)
    }

    @Test fun `the host in the message is the proxy's when the row goes through one`() = runTest {
        val job = dl("https://www.youtube.com/watch?v=abc", listOf("--proxy", "socks5://192.168.1.5:1080"))
        val info = (terminalOf(JobDriver(failedRun(timeout), FakeTranscodePort(), LocalNetworkGuard { _, _ -> true }), job) as Outcome.Failed).info
        assertEquals(YtdlpErrors.localNetworkBlocked("192.168.1.5"), info.message)
    }

    @Test fun `a guard that throws never changes the verdict of a failed download`() = runTest {
        val outcome = terminalOf(JobDriver(failedRun(timeout), FakeTranscodePort(), LocalNetworkGuard { _, _ -> error("permission lookup failed") }))
        assertEquals(timeout, (outcome as Outcome.Failed).info.message)
    }

    @Test fun `a successful download never asks the guard`() = runTest {
        var asked = false
        val port = FakeDownloadPort { flow { emit(EngineEvent.Completed(0)) } }
        val outcome = terminalOf(JobDriver(port, FakeTranscodePort(), LocalNetworkGuard { _, _ -> asked = true; true }))
        assertEquals(Outcome.Succeeded, outcome)
        assertFalse(asked)
    }

    @Test fun `the none guard is the default and blocks nothing`() = runTest {
        assertFalse(LocalNetworkGuard.None.blocks("http://nas/x", emptyList()))
    }
}
