package com.sieve.queue.service

import com.sieve.engine.model.DownloadProgress
import com.sieve.engine.repo.EngineEvent
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueuePersistence
import com.sieve.queue.core.QueueState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueManagerTest {
    private fun dl(id: String) =
        QueueJob(id, JobSpec.Download("https://$id", listOf("-f", "best")), OutputRequest("Downloads/Sieve", "%(title)s.%(ext)s"))

    private fun manager(
        dlPort: DownloadPort,
        txPort: TranscodePort = FakeTranscodePort(),
        persistence: QueuePersistence = InMemoryPersistence(),
        output: FakeOutputProvider = FakeOutputProvider(),
        clock: Clock = FakeClock(),
        maxDownloads: Int = 2,
    ): Pair<QueueManager, FakeOutputProvider> {
        val m = QueueManager(
            JobDriver(dlPort, txPort), dlPort, txPort, persistence, output, clock,
            initial = QueueState(maxDownloads = maxDownloads),
        )
        return m to output
    }

    @Test fun `enqueue drains a job to COMPLETED and persists`() = runTest {
        val port = FakeDownloadPort {
            flow {
                emit(EngineEvent.Progress(DownloadProgress(0.5f, "1MiB/s", "00:05", "1/2")))
                emit(EngineEvent.Completed(0))
            }
        }
        val persistence = InMemoryPersistence()
        val (m, out) = manager(port, persistence = persistence)
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.status == DownloadStatus.COMPLETED }
        assertTrue(out.prepared.contains("a"))
        assertTrue(out.finalized.contains("a"))
        assertEquals(DownloadStatus.COMPLETED, persistence.loadAll().first().status)
    }

    @Test fun `permit limit prevents third concurrent download`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakeDownloadPort {
            flow {
                emit(EngineEvent.Progress(DownloadProgress(0.1f)))
                gate.await()
                emit(EngineEvent.Completed(0))
            }
        }
        val (m, _) = manager(port, maxDownloads = 2)
        m.start(backgroundScope)
        m.enqueue(dl("a")); m.enqueue(dl("b")); m.enqueue(dl("c"))
        advanceUntilIdle()
        val s = m.state.value
        assertEquals(2, s.jobs.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PREPARING })
        assertEquals(1, s.jobs.count { it.status == DownloadStatus.QUEUED })
        gate.complete(Unit)
    }

    @Test fun `pause stamps reason before cancel and lands PAUSED`() = runTest {
        val port = CancellableDownloadPort()
        val (m, _) = manager(port)
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.progress?.fraction != null } // running, first progress seen
        m.pause("a")
        m.state.first { it.job("a")?.status == DownloadStatus.PAUSED }
        assertTrue(port.cancelled.contains("a"))
        assertEquals(DownloadStatus.PAUSED, m.state.value.job("a")!!.status)
    }

    @Test fun `rehydrate reverts running to queued and re-drains`() = runTest {
        val persistence = InMemoryPersistence()
        persistence.upsert(dl("a").copy(status = DownloadStatus.RUNNING, position = 1))
        val port = FakeDownloadPort { flow { emit(EngineEvent.Completed(0)) } }
        val (m, _) = manager(port, persistence = persistence)
        m.rehydrate()
        m.start(backgroundScope)
        m.state.first { it.job("a")?.status == DownloadStatus.COMPLETED }
        assertEquals(DownloadStatus.COMPLETED, m.state.value.job("a")!!.status)
    }
}
