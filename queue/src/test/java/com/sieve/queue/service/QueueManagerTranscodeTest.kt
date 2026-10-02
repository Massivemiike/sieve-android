package com.sieve.queue.service

import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import com.sieve.transcode.runner.FfmpegProgress
import com.sieve.transcode.runner.TranscodeEvent
import com.sieve.transcode.runner.TranscodeJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A transcode port that behaves like ffmpeg: the job runs until a cancel writes 'q', after which it
 * finalizes the (truncated) output and exits 0 — NOT non-zero. [cancelFailure] makes the cancel itself
 * throw, like `q` written to an already-exited process used to.
 */
private class QuitOnCancelPort(private val cancelFailure: Throwable? = null) : TranscodePort {
    private val exits = HashMap<String, CompletableDeferred<TranscodeEvent.Done>>()
    val cancelled = mutableListOf<String>()
    private fun exit(id: String) = exits.getOrPut(id) { CompletableDeferred() }

    override fun run(id: String, job: TranscodeJob): Flow<TranscodeEvent> = flow {
        emit(TranscodeEvent.Progress(FfmpegProgress(outTimeUs = 3_000_000L, percent = 0.3, speed = 1.0, speedRaw = "1x")))
        emit(exit(id).await())
    }

    override suspend fun cancel(id: String, graceMs: Long) {
        cancelled += id
        cancelFailure?.let { throw it }
        exit(id).complete(TranscodeEvent.Done(0, null, "")) // graceful 'q' stop
    }

    fun failWith(id: String, code: Int) { exit(id).complete(TranscodeEvent.Done(code, "boom", "tail")) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class QueueManagerTranscodeTest {
    private fun tx(id: String) = QueueJob(
        id,
        JobSpec.Transcode(inputPath = "/cache/tx-src-$id.mkv", presetArgs = listOf("-c:v", "libx264"), totalDurationSec = 10.0, usedHardwareEncoder = false),
        OutputRequest("Downloads/Sieve", "$id.mp4"),
    )

    private class Rig(val m: QueueManager, val out: FakeOutputProvider, val released: MutableList<String>, val completed: MutableList<String>)

    private fun rig(
        port: TranscodePort,
        maxTranscodes: Int = 2,
        persistence: InMemoryPersistence = InMemoryPersistence(),
        releaseFailure: Throwable? = null,
    ): Rig {
        val dl = FakeDownloadPort()
        val out = FakeOutputProvider()
        val released = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val m = QueueManager(
            JobDriver(dl, port), dl, port, persistence, out, FakeClock(),
            initial = QueueState(maxTranscodes = maxTranscodes),
            onCompleted = { completed += it.id },
            releaseSource = { releaseFailure?.let { t -> throw t }; released += it.id },
        )
        return Rig(m, out, released, completed)
    }

    // --- cancel / pause while ffmpeg exits 0 on 'q' -------------------------------------------------

    @Test fun `cancelling a running transcode is CANCELLED, never saved as finished`() = runTest {
        val port = QuitOnCancelPort()
        val r = rig(port)
        r.m.start(backgroundScope)
        r.m.enqueue(tx("t"))
        r.m.state.first { it.job("t")?.progress?.fraction != null }

        r.m.cancel("t")
        r.m.state.first { it.job("t")?.status?.isTerminal == true }
        runCurrent() // the terminal's discard/release run just after its dispatch

        assertEquals(DownloadStatus.CANCELLED, r.m.state.value.job("t")!!.status)
        assertTrue("the truncated output must not reach the user's folder", r.out.finalized.isEmpty())
        assertTrue("no 'Transcoded' notification", r.completed.isEmpty())
        assertEquals(listOf("t"), r.out.discarded) // partial work dir dropped
    }

    @Test fun `pausing a running transcode is PAUSED and resumable, not completed`() = runTest {
        val port = QuitOnCancelPort()
        val r = rig(port)
        r.m.start(backgroundScope)
        r.m.enqueue(tx("t"))
        r.m.state.first { it.job("t")?.progress?.fraction != null }

        r.m.pause("t")
        r.m.state.first { it.job("t")?.status?.isTerminal == true || it.job("t")?.status == DownloadStatus.PAUSED }
        runCurrent()

        assertEquals(DownloadStatus.PAUSED, r.m.state.value.job("t")!!.status)
        assertTrue(r.out.finalized.isEmpty())
        assertTrue(r.completed.isEmpty())
        assertTrue("a paused job still needs its source", r.released.isEmpty())
    }

    // --- a failed kill must never escape -------------------------------------------------------------

    @Test fun `a transcode cancel that throws does not propagate out of cancel or pause`() = runTest {
        val port = QuitOnCancelPort(cancelFailure = IOException("Stream closed"))
        val r = rig(port)
        r.m.start(backgroundScope)
        r.m.enqueue(tx("t"))
        r.m.state.first { it.job("t")?.progress?.fraction != null }

        r.m.pause("t")  // each of these runs in an app-scope launch with no exception handler
        r.m.cancel("t")

        assertEquals(listOf("t", "t"), port.cancelled)
    }

    // --- materialized source copies ------------------------------------------------------------------

    @Test fun `a completed transcode releases its source after saving the output`() = runTest {
        val port = FakeTranscodePort { flow { emit(TranscodeEvent.Done(0, null, "")) } }
        val r = rig(port)
        r.m.start(backgroundScope)
        r.m.enqueue(tx("t"))
        r.m.state.first { it.job("t")?.status == DownloadStatus.COMPLETED }

        assertEquals(listOf("t"), r.out.finalized)
        assertEquals(listOf("t"), r.released)
    }

    @Test fun `a user-cancelled running transcode releases its source`() = runTest {
        val port = QuitOnCancelPort()
        val r = rig(port)
        r.m.start(backgroundScope)
        r.m.enqueue(tx("t"))
        r.m.state.first { it.job("t")?.progress?.fraction != null }
        r.m.cancel("t")
        r.m.state.first { it.job("t")?.status == DownloadStatus.CANCELLED }
        runCurrent()

        assertEquals(listOf("t"), r.released)
    }

    @Test fun `cancelling a queued transcode releases its source`() = runTest {
        val port = QuitOnCancelPort()
        val r = rig(port, maxTranscodes = 1)
        r.m.start(backgroundScope)
        r.m.enqueue(tx("busy")); r.m.enqueue(tx("waiting"))
        r.m.state.first { it.job("busy")?.progress?.fraction != null }
        assertEquals(DownloadStatus.QUEUED, r.m.state.value.job("waiting")!!.status)

        r.m.cancel("waiting")

        assertEquals(DownloadStatus.CANCELLED, r.m.state.value.job("waiting")!!.status)
        assertEquals(listOf("waiting"), r.released)
    }

    @Test fun `cancelling a paused transcode releases its source`() = runTest {
        val port = QuitOnCancelPort()
        val r = rig(port)
        r.m.start(backgroundScope)
        r.m.enqueue(tx("t"))
        r.m.state.first { it.job("t")?.progress?.fraction != null }
        r.m.pause("t")
        r.m.state.first { it.job("t")?.status == DownloadStatus.PAUSED }
        assertTrue(r.released.isEmpty())

        r.m.cancel("t")

        assertEquals(listOf("t"), r.released)
    }

    // Retry re-reads the persisted inputPath, so a failed row keeps its source until it is dropped.
    @Test fun `a failed transcode keeps its source for Retry until the row is removed`() = runTest {
        val port = QuitOnCancelPort()
        val r = rig(port)
        r.m.start(backgroundScope)
        r.m.enqueue(tx("t"))
        r.m.state.first { it.job("t")?.progress?.fraction != null }
        port.failWith("t", 1)
        r.m.state.first { it.job("t")?.status == DownloadStatus.FAILED }
        assertTrue("Retry still needs the input", r.released.isEmpty())

        r.m.remove("t")

        assertEquals(listOf("t"), r.released)
    }

    @Test fun `clearFinished releases the sources of the rows it drops`() = runTest {
        val failed = tx("bad").copy(status = DownloadStatus.FAILED, position = 1)
        val live = tx("live").copy(status = DownloadStatus.PAUSED, position = 2)
        val persistence = InMemoryPersistence().also { it.upsertAll(listOf(failed, live)) }
        val port = FakeTranscodePort()
        val dl = FakeDownloadPort()
        val released = mutableListOf<String>()
        val m = QueueManager(
            JobDriver(dl, port), dl, port, persistence, FakeOutputProvider(), FakeClock(),
            initial = QueueState(jobs = listOf(failed, live), maxTranscodes = 0),
            releaseSource = { released += it.id },
        )
        m.clearFinished()

        assertEquals(listOf("bad"), released)
    }

    @Test fun `a source release that throws never blocks completion`() = runTest {
        val port = FakeTranscodePort { flow { emit(TranscodeEvent.Done(0, null, "")) } }
        val r = rig(port, releaseFailure = IOException("EBUSY"))
        r.m.start(backgroundScope)
        r.m.enqueue(tx("t"))
        r.m.state.first { it.job("t")?.status == DownloadStatus.COMPLETED }

        assertEquals(listOf("t"), r.completed)
    }

    @Test fun `downloads never reach the source release`() = runTest {
        val dl = FakeDownloadPort { flow { emit(com.sieve.engine.repo.EngineEvent.Completed(0)) } }
        val tp = FakeTranscodePort()
        val released = mutableListOf<String>()
        val m = QueueManager(
            JobDriver(dl, tp), dl, tp, InMemoryPersistence(), FakeOutputProvider(), FakeClock(),
            initial = QueueState(maxDownloads = 1), releaseSource = { released += it.id },
        )
        m.start(backgroundScope)
        m.enqueue(QueueJob("d", JobSpec.Download("https://d", emptyList()), OutputRequest("d", "o")))
        m.state.first { it.job("d")?.status == DownloadStatus.COMPLETED }
        m.remove("d")

        assertTrue(released.isEmpty())
    }
}
