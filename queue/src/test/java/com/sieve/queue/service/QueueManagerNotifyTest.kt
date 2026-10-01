package com.sieve.queue.service

import com.sieve.engine.repo.EngineEvent
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The queue's completion / failure callbacks (what drives the "Downloaded / Failed" notifications). */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueManagerNotifyTest {
    private fun dl(id: String) = QueueJob(id, JobSpec.Download("u$id", listOf("-f", "best")), OutputRequest("d", "o"), title = "T$id")

    private class Recorder {
        val completed = mutableListOf<QueueJob>()
        val failed = mutableListOf<QueueJob>()
    }

    private fun manager(
        port: DownloadPort,
        rec: Recorder,
        output: FakeOutputProvider = FakeOutputProvider(),
        clock: Clock = FakeClock(),
        maxDownloads: Int = 1,
        onFailed: suspend (QueueJob) -> Unit = { rec.failed += it },
    ) = QueueManager(
        JobDriver(port, FakeTranscodePort()), port, FakeTranscodePort(), InMemoryPersistence(), output, clock,
        initial = QueueState(maxDownloads = maxDownloads), onCompleted = { rec.completed += it }, onFailed = onFailed,
    )

    @Test fun `a finished download is announced once, in its COMPLETED form with the saved location`() = runTest {
        val rec = Recorder()
        val port = FakeDownloadPort { flow { emit(EngineEvent.Completed(0)) } }
        val m = manager(port, rec, FakeOutputProvider(finalUriPrefix = "content://media/external/downloads/"))
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.status == DownloadStatus.COMPLETED }

        val job = rec.completed.single()
        assertEquals(DownloadStatus.COMPLETED, job.status)
        assertEquals("content://media/external/downloads/a", job.filePath)
        assertTrue(rec.failed.isEmpty())
    }

    @Test fun `a permanent failure is announced once with the FAILED job and its error`() = runTest {
        val rec = Recorder()
        val port = FakeDownloadPort { flow { emit(EngineEvent.Failed("ERROR: Video unavailable")) } }
        val m = manager(port, rec)
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.status == DownloadStatus.FAILED }
        runCurrent()

        val job = rec.failed.single()
        assertEquals(DownloadStatus.FAILED, job.status)
        assertTrue(job.error!!.contains("Video unavailable"))
        assertTrue(rec.completed.isEmpty())
    }

    @Test fun `a transient failure is announced only when the automatic retry has also failed`() = runTest {
        val rec = Recorder()
        val port = FakeDownloadPort { flow { emit(EngineEvent.Failed("Network timed out")) } }
        val clock = object : Clock { override fun nowMs() = testScheduler.currentTime }
        val m = manager(port, rec, clock = clock)
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(DownloadStatus.QUEUED, m.state.value.job("a")!!.status) // waiting to retry
        assertTrue("no failure yet", rec.failed.isEmpty())

        advanceTimeBy(5_001)
        m.state.first { it.job("a")?.status == DownloadStatus.FAILED }
        runCurrent()
        assertEquals(1, rec.failed.size)
    }

    @Test fun `cancelling or pausing never announces a failure`() = runTest {
        val rec = Recorder()
        val port = CancellableDownloadPort()
        val m = manager(port, rec, maxDownloads = 2)
        m.start(backgroundScope)
        m.enqueue(dl("a")); m.enqueue(dl("b"))
        m.state.first { s -> s.jobs.size == 2 && s.jobs.all { it.progress.fraction != null } }
        m.pause("a")
        m.state.first { it.job("a")?.status == DownloadStatus.PAUSED }
        m.cancel("b")
        m.state.first { it.job("b")?.status == DownloadStatus.CANCELLED }
        runCurrent()

        assertTrue(rec.failed.isEmpty())
        assertTrue(rec.completed.isEmpty())
    }

    @Test fun `a failed save is announced as a failure, not a completion`() = runTest {
        val rec = Recorder()
        val port = FakeDownloadPort { flow { emit(EngineEvent.Completed(0)) } }
        val m = manager(port, rec, FakeOutputProvider(failFinalize = true))
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.status == DownloadStatus.FAILED }
        runCurrent()

        assertTrue(rec.completed.isEmpty())
        val job = rec.failed.single()
        assertTrue(job.error!!.startsWith("saving output failed"))
    }

    @Test fun `a notification callback that throws never breaks the queue`() = runTest {
        val rec = Recorder()
        val port = FakeDownloadPort { id -> flow { emit(if (id == "a") EngineEvent.Failed("ERROR: Video unavailable") else EngineEvent.Completed(0)) } }
        val m = manager(port, rec, onFailed = { throw IllegalStateException("notification boom") })
        m.start(backgroundScope)
        m.enqueue(dl("a")); m.enqueue(dl("b"))
        m.state.first { it.job("a")?.status == DownloadStatus.FAILED && it.job("b")?.status == DownloadStatus.COMPLETED }
        assertEquals(DownloadStatus.FAILED, m.state.value.job("a")!!.status)
    }
}
