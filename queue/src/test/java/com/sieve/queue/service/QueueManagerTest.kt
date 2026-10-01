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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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

    private fun active(m: QueueManager) =
        m.state.value.jobs.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PREPARING }

    // advanceUntilIdle() skips backgroundScope work (the manager runs there); runCurrent() drains it.
    @Test fun `followLimits raising the cap admits waiting downloads without a restart`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakeDownloadPort { flow { emit(EngineEvent.Progress(DownloadProgress(0.1f))); gate.await(); emit(EngineEvent.Completed(0)) } }
        val (m, _) = manager(port, maxDownloads = 1)
        val limits = MutableStateFlow(1 to 1)
        m.start(backgroundScope)
        backgroundScope.launch { m.followLimits(limits) }
        m.enqueue(dl("a")); m.enqueue(dl("b")); m.enqueue(dl("c"))
        runCurrent()
        assertEquals(1, active(m))
        assertEquals(2, m.state.value.jobs.count { it.status == DownloadStatus.QUEUED })

        limits.value = 3 to 1
        runCurrent()
        assertEquals(3, active(m))
        assertEquals(0, m.state.value.jobs.count { it.status == DownloadStatus.QUEUED })
        gate.complete(Unit)
    }

    @Test fun `followLimits lowering the cap keeps running jobs and holds back new ones`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakeDownloadPort { flow { emit(EngineEvent.Progress(DownloadProgress(0.1f))); gate.await(); emit(EngineEvent.Completed(0)) } }
        val (m, _) = manager(port, maxDownloads = 3)
        val limits = MutableStateFlow(3 to 1)
        m.start(backgroundScope)
        backgroundScope.launch { m.followLimits(limits) }
        m.enqueue(dl("a")); m.enqueue(dl("b")); m.enqueue(dl("c"))
        runCurrent()
        assertEquals(3, active(m))

        limits.value = 1 to 1
        m.enqueue(dl("d"))
        runCurrent()
        assertEquals(3, active(m))                                           // none killed
        assertEquals(DownloadStatus.QUEUED, m.state.value.job("d")!!.status) // d waits for a free slot

        gate.complete(Unit)
        m.state.first { it.job("d")?.status == DownloadStatus.COMPLETED }
        assertTrue(m.state.value.jobs.all { it.status == DownloadStatus.COMPLETED })
    }

    @Test fun `followLimits clamps out-of-range values and applies the transcode cap`() = runTest {
        val (m, _) = manager(FakeDownloadPort(), maxDownloads = 2)
        m.followLimits(flowOf(0 to 99))
        assertEquals(1, m.state.value.maxDownloads)
        assertEquals(4, m.state.value.maxTranscodes)
        m.followLimits(flowOf(50 to 2))
        assertEquals(10, m.state.value.maxDownloads)
        assertEquals(2, m.state.value.maxTranscodes)
    }

    @Test fun `remove drops a finished row, its persisted row and its leftover work dir`() = runTest {
        val port = FakeDownloadPort { flow { emit(EngineEvent.Completed(0)) } }
        val persistence = InMemoryPersistence()
        val (m, out) = manager(port, persistence = persistence)
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.status == DownloadStatus.COMPLETED }
        assertTrue(out.discarded.isEmpty())

        m.remove("a")
        assertEquals(null, m.state.value.job("a"))
        assertTrue(persistence.loadAll().isEmpty())
        assertEquals(listOf("a"), out.discarded)
    }

    @Test fun `remove clears a failed row too`() = runTest {
        val port = FakeDownloadPort { flow { emit(EngineEvent.Completed(1)) } }
        val persistence = InMemoryPersistence()
        val (m, _) = manager(port, persistence = persistence)
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.status == DownloadStatus.FAILED }

        m.remove("a")
        assertEquals(null, m.state.value.job("a"))
        assertTrue(persistence.loadAll().isEmpty())
    }

    @Test fun `remove ignores a row that is still live`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakeDownloadPort { flow { emit(EngineEvent.Progress(DownloadProgress(0.1f))); gate.await(); emit(EngineEvent.Completed(0)) } }
        val (m, out) = manager(port, maxDownloads = 1)
        m.start(backgroundScope)
        m.enqueue(dl("a")); m.enqueue(dl("b"))
        m.state.first { it.job("a")?.status == DownloadStatus.RUNNING }

        m.remove("a"); m.remove("b")
        assertEquals(listOf("a", "b"), m.state.value.jobs.map { it.id })
        assertTrue(out.discarded.isEmpty())
        gate.complete(Unit)
    }

    @Test fun `clearFinished removes finished rows everywhere but never touches live ones`() = runTest {
        val persistence = InMemoryPersistence()
        val jobs = listOf(
            dl("q").copy(status = DownloadStatus.QUEUED, position = 1), dl("z").copy(status = DownloadStatus.PAUSED, position = 2),
            dl("ok").copy(status = DownloadStatus.COMPLETED, position = 3), dl("bad").copy(status = DownloadStatus.FAILED, position = 4),
            dl("x").copy(status = DownloadStatus.CANCELLED, position = 5),
        )
        persistence.upsertAll(jobs)
        val out = FakeOutputProvider()
        val port = FakeDownloadPort()
        val m = QueueManager(
            JobDriver(port, FakeTranscodePort()), port, FakeTranscodePort(), persistence, out, FakeClock(),
            initial = QueueState(jobs = jobs, maxDownloads = 0), // nothing drains
        )
        m.clearFinished()

        assertEquals(listOf("q", "z"), m.state.value.jobs.map { it.id })
        assertEquals(listOf("q", "z"), persistence.loadAll().map { it.id })
        assertEquals(setOf("ok", "bad", "x"), out.discarded.toSet())
    }

    @Test fun `finalized output location is recorded on the completed row`() = runTest {
        val port = FakeDownloadPort { flow { emit(EngineEvent.Completed(0)) } }
        val persistence = InMemoryPersistence()
        val out = FakeOutputProvider(finalUriPrefix = "content://media/external/downloads/")
        val completed = mutableListOf<QueueJob>()
        val m = QueueManager(
            JobDriver(port, FakeTranscodePort()), port, FakeTranscodePort(), persistence, out, FakeClock(),
            initial = QueueState(maxDownloads = 1), onCompleted = { completed += it },
        )
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.status == DownloadStatus.COMPLETED }

        assertEquals("content://media/external/downloads/a", m.state.value.job("a")!!.filePath)
        assertEquals("content://media/external/downloads/a", persistence.loadAll().single().filePath)
        assertEquals("content://media/external/downloads/a", completed.single().filePath) // the callback sees it too
        assertEquals(DownloadStatus.COMPLETED, completed.single().status)
    }

    @Test fun `a sink without a Uri records its display path`() = runTest {
        val port = FakeDownloadPort { flow { emit(EngineEvent.Completed(0)) } }
        val (m, _) = manager(port)
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.status == DownloadStatus.COMPLETED }
        assertEquals("/final/a", m.state.value.job("a")!!.filePath)
    }

    @Test fun `cancelling a paused job cleans up its partial work dir`() = runTest {
        val port = CancellableDownloadPort()
        val (m, out) = manager(port)
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.progress?.fraction != null }
        m.pause("a")
        m.state.first { it.job("a")?.status == DownloadStatus.PAUSED }
        assertTrue(out.discarded.isEmpty())

        m.cancel("a")
        assertEquals(DownloadStatus.CANCELLED, m.state.value.job("a")!!.status)
        assertEquals(listOf("a"), out.discarded)
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

    @Test fun `pause during prepare lands PAUSED without spawning`() = runTest {
        val gate = CompletableDeferred<Unit>()
        // Port would complete the job if it ever spawned — so PAUSED (not COMPLETED) proves the short-circuit.
        val port = FakeDownloadPort { flow { emit(EngineEvent.Completed(0)) } }
        val (m, _) = manager(port, output = FakeOutputProvider(prepareGate = gate))
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.status == DownloadStatus.PREPARING } // stuck on the prepare gate
        m.pause("a")                                                      // stamps PAUSE while PREPARING
        gate.complete(Unit)                                              // release prepare
        m.state.first { it.job("a")?.status == DownloadStatus.PAUSED }
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
