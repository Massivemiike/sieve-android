package com.sieve.queue.service

import com.sieve.engine.repo.EngineEvent
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.FinalLocation
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import com.sieve.transcode.runner.TranscodeEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * yt-dlp exits non-zero when ONE playlist entry fails, after saving the others. A FAILED run must
 * hand the finished files to user storage before its work dir goes — never delete them with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueManagerSalvageTest {
    private fun dl(id: String) = QueueJob(id, JobSpec.Download("u$id", listOf("-f", "best")), OutputRequest("d", "o"), title = "T$id")

    /** The engine's failure shape: yt-dlp's stderr as one error Log, then the exit code. */
    private val privateEntry = FakeDownloadPort {
        flow {
            emit(EngineEvent.Log("ERROR: [youtube] abc: Private video. Sign in if you've been granted access", null, true))
            emit(EngineEvent.Completed(1))
        }
    }

    private fun manager(
        port: DownloadPort,
        output: FakeOutputProvider,
        failed: MutableList<QueueJob> = mutableListOf(),
        txPort: TranscodePort = FakeTranscodePort(),
    ) = QueueManager(
        JobDriver(port, txPort), port, txPort, InMemoryPersistence(), output, FakeClock(),
        initial = QueueState(maxDownloads = 1), onFailed = { failed += it },
    )

    @Test fun `a failed run that left finished media saves it and does not discard the work dir`() = runTest {
        val output = FakeOutputProvider(salvagedTo = FinalLocation("Download/Sieve/Track 01.mp3", "content://media/external/downloads/9"))
        val failed = mutableListOf<QueueJob>()
        val m = manager(privateEntry, output, failed)
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.status == DownloadStatus.FAILED }
        runCurrent()

        assertEquals(listOf("a"), output.salvaged)
        assertTrue("finished files must not be wiped", output.discarded.isEmpty())
        val row = m.state.value.job("a")!!
        assertEquals(DownloadStatus.FAILED, row.status)                              // the bad entry still failed ...
        assertTrue(row.error!!.contains("Private video"))                             // ... and says so
        assertEquals("content://media/external/downloads/9", row.filePath)           // ... but the rest is kept and reachable
        assertEquals("content://media/external/downloads/9", failed.single().filePath) // the failure notification sees the saved row
    }

    @Test fun `a failed run with nothing finished still discards the work dir`() = runTest {
        val output = FakeOutputProvider(salvagedTo = null)
        val m = manager(privateEntry, output)
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.status == DownloadStatus.FAILED }
        runCurrent()

        assertEquals(listOf("a"), output.salvaged)
        assertEquals(listOf("a"), output.discarded)
        assertNull(m.state.value.job("a")!!.filePath)
    }

    @Test fun `a salvage that fails keeps the work dir so the finished files are not lost`() = runTest {
        val output = FakeOutputProvider(failSalvage = true)
        val failed = mutableListOf<QueueJob>()
        val m = manager(privateEntry, output, failed)
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.status == DownloadStatus.FAILED }
        runCurrent()

        assertTrue("a failed copy must leave the work dir for Retry", output.discarded.isEmpty())
        assertEquals(1, failed.size)                                                  // still announced
        assertEquals(DownloadStatus.FAILED, m.state.value.job("a")!!.status)
    }

    @Test fun `a failed transcode never salvages - its partial output is not a result`() = runTest {
        val output = FakeOutputProvider(salvagedTo = FinalLocation("Download/Sieve/clip.mp4", null))
        val tx = FakeTranscodePort { flow { emit(TranscodeEvent.Done(1, "Conversion failed!", "")) } }
        val m = manager(FakeDownloadPort(), output, txPort = tx)
        m.start(backgroundScope)
        m.enqueue(QueueJob("t", JobSpec.Transcode("/in.mkv", emptyList(), 10.0, false), OutputRequest("d", "o"), title = "clip"))
        m.state.first { it.job("t")?.status == DownloadStatus.FAILED }
        runCurrent()

        assertTrue(output.salvaged.isEmpty())
        assertEquals(listOf("t"), output.discarded)
    }

    @Test fun `a user cancel discards everything and never salvages`() = runTest {
        val output = FakeOutputProvider(salvagedTo = FinalLocation("Download/Sieve/x.mp4", null))
        val m = manager(CancellableDownloadPort(), output)
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.progress?.fraction != null }
        m.cancel("a")
        m.state.first { it.job("a")?.status == DownloadStatus.CANCELLED }
        runCurrent()

        assertTrue(output.salvaged.isEmpty())
        assertEquals(listOf("a"), output.discarded)
    }
}
