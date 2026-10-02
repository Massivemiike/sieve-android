package com.sieve.queue.service

import com.sieve.engine.model.DownloadProgress
import com.sieve.engine.repo.EngineEvent
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.FinalLocation
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import com.sieve.transcode.runner.TranscodeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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

    // The copy can take minutes. While it runs the row must not offer Retry / Remove (they would race the copy:
    // a Retry wedged the job in PREPARING, a Remove deleted the work dir under it), and the queue must not read
    // as idle (the service would stop with the copy half done) — so FAILED only appears once the files are saved.
    @Test fun `the row stays running while the finished files are copied and shows FAILED only after`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val port = FakeDownloadPort { runs++; privateEntry.script(it) }
        val output = FakeOutputProvider(
            salvagedTo = FinalLocation("Download/Sieve/Track 01.mp3", "content://media/external/downloads/9"), salvageGate = gate,
        )
        val failed = mutableListOf<QueueJob>()
        val m = manager(port, output, failed)
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        runCurrent() // runs until the salvage waits at the gate

        assertEquals(listOf("a"), output.salvaged)                                       // the copy is under way ...
        assertEquals(DownloadStatus.RUNNING, m.state.value.job("a")!!.status)             // ... and the row is still live
        assertTrue(failed.isEmpty())
        m.retry("a"); m.remove("a"); m.clearFinished()                                    // none of these may reach a live row
        runCurrent()
        assertEquals(DownloadStatus.RUNNING, m.state.value.job("a")!!.status)
        assertTrue("the work dir under the copy must stay", output.discarded.isEmpty())

        gate.complete(Unit)
        m.state.first { it.job("a")?.status == DownloadStatus.FAILED }
        runCurrent()

        assertEquals(1, runs)
        assertEquals("content://media/external/downloads/9", m.state.value.job("a")!!.filePath)
        assertEquals(1, failed.size)
        assertEquals("content://media/external/downloads/9", failed.single().filePath)
    }

    // The failed run's coroutine stays in runningJobs until its finally block; a Retry in that last instant (here
    // held open by a slow failure callback) used to be claimed by drain() but refused by launchJob — PREPARING forever.
    @Test fun `a Retry pressed while the failed run is still unwinding runs once it has unwound`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val port = FakeDownloadPort {
            runs++
            if (runs == 1) privateEntry.script(it) else flow { emit(EngineEvent.Progress(DownloadProgress(0.1f))); awaitCancellation() }
        }
        val txPort = FakeTranscodePort()
        val m = QueueManager(
            JobDriver(port, txPort), port, txPort, InMemoryPersistence(), FakeOutputProvider(), FakeClock(),
            initial = QueueState(maxDownloads = 1), onFailed = { gate.await() },
        )
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        runCurrent() // FAILED is out; the run is still inside the failure callback
        assertEquals(DownloadStatus.FAILED, m.state.value.job("a")!!.status)

        m.retry("a")
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(DownloadStatus.RUNNING, m.state.value.job("a")!!.status)
        assertEquals(2, runs)
    }
}
