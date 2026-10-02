package com.sieve.queue.service

import com.sieve.engine.repo.EngineEvent
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueuePersistence
import com.sieve.queue.core.QueueState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Restoring the persisted queue on launch: complete, once, and without trampling live work. */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueManagerRehydrateTest {
    private fun row(id: String, status: DownloadStatus, position: Long = 1) =
        QueueJob(id, JobSpec.Download("https://$id", listOf("-f", "best")), OutputRequest("d", "o"), status = status, position = position, title = "T$id")

    private fun manager(persistence: QueuePersistence, port: DownloadPort = FakeDownloadPort()) = QueueManager(
        JobDriver(port, FakeTranscodePort()), port, FakeTranscodePort(), persistence, FakeOutputProvider(), FakeClock(),
        initial = QueueState(maxDownloads = 2),
    )

    @Test fun `every persisted row comes back - history included - and in-flight work resumes as queued`() = runTest {
        val persistence = InMemoryPersistence()
        persistence.upsertAll(
            listOf(
                row("done", DownloadStatus.COMPLETED, 1), row("bad", DownloadStatus.FAILED, 2),
                row("gone", DownloadStatus.CANCELLED, 3), row("cut", DownloadStatus.RUNNING, 4),
                row("held", DownloadStatus.PAUSED, 5),
            ),
        )
        val m = manager(persistence)
        assertFalse(m.rehydrated.value)

        m.rehydrate()

        val s = m.state.value
        assertEquals(5, s.jobs.size)
        assertEquals(DownloadStatus.COMPLETED, s.job("done")!!.status)
        assertEquals(DownloadStatus.FAILED, s.job("bad")!!.status)
        assertEquals(DownloadStatus.CANCELLED, s.job("gone")!!.status)
        assertEquals(DownloadStatus.QUEUED, s.job("cut")!!.status)    // process died mid-download
        assertEquals(DownloadStatus.QUEUED, s.job("held")!!.status)   // desktop parity: paused rows restore as queued
        assertEquals(DownloadStatus.QUEUED, persistence.loadAll().first { it.id == "cut" }.status) // and that is persisted
        assertTrue(m.rehydrated.value)
    }

    @Test fun `rehydrate runs once - a second call changes nothing`() = runTest {
        val persistence = InMemoryPersistence()
        persistence.upsert(row("a", DownloadStatus.COMPLETED))
        val m = manager(persistence)
        m.rehydrate()
        m.remove("a")
        persistence.upsert(row("late", DownloadStatus.COMPLETED, 2)) // appears in the store after the first load

        m.rehydrate()

        assertTrue(m.state.value.jobs.isEmpty()) // neither the removed row nor the late one is resurrected
    }

    @Test fun `a job enqueued before the load finished survives it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val inner = InMemoryPersistence().also { it.upsert(row("old", DownloadStatus.FAILED, 1)) }
        val slow = object : QueuePersistence by inner {
            override suspend fun loadAll(): List<QueueJob> { gate.await(); return inner.loadAll() }
        }
        val m = manager(slow)
        val loading = backgroundScope.launch { m.rehydrate() }
        runCurrent()

        m.enqueue(row("fresh", DownloadStatus.QUEUED, 1)) // e.g. a share-intent enqueue racing the Room read
        gate.complete(Unit)
        loading.join()

        assertEquals(setOf("old", "fresh"), m.state.value.jobs.map { it.id }.toSet())
        assertEquals(DownloadStatus.QUEUED, m.state.value.job("fresh")!!.status)
    }

    @Test fun `a job enqueued before the load queues behind the restored work, not tied with it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val inner = InMemoryPersistence().also {
            it.upsert(row("old1", DownloadStatus.RUNNING, 1)); it.upsert(row("old2", DownloadStatus.QUEUED, 2))
        }
        val slow = object : QueuePersistence by inner {
            override suspend fun loadAll(): List<QueueJob> { gate.await(); return inner.loadAll() }
        }
        val m = manager(slow)
        val loading = backgroundScope.launch { m.rehydrate() }
        runCurrent()

        m.enqueue(row("fresh", DownloadStatus.QUEUED, 1)) // takes position 1 from the still-empty queue: ties with "old1"
        gate.complete(Unit)
        loading.join()

        val order = listOf("old1", "old2", "fresh")
        assertEquals(order, m.state.value.jobs.sortedBy { it.position }.map { it.id })
        assertEquals(order, inner.loadAll().map { it.id }) // and the store agrees, so the next launch keeps it
        assertEquals(order.indices.map { it.toLong() + 1 }, m.state.value.jobs.sortedBy { it.position }.map { it.position }) // no ties
    }

    @Test fun `a job already running is not reverted or doubled by a late rehydrate`() = runTest {
        val persistence = InMemoryPersistence()
        val port = FakeDownloadPort { flow { emit(EngineEvent.Progress(com.sieve.engine.model.DownloadProgress(0.2f))); awaitCancellation() } }
        val m = manager(persistence, port)
        m.start(backgroundScope)
        m.enqueue(row("live", DownloadStatus.QUEUED))
        m.state.first { it.job("live")?.status == DownloadStatus.RUNNING }
        persistence.upsert(row("old", DownloadStatus.FAILED, 9)) // history from an earlier session

        m.rehydrate()

        assertEquals(DownloadStatus.RUNNING, m.state.value.job("live")!!.status)
        assertEquals(1, m.state.value.jobs.count { it.id == "live" })
        assertEquals(DownloadStatus.FAILED, m.state.value.job("old")!!.status)
    }

    @Test fun `a store that cannot be read still ends the load, with an empty queue`() = runTest {
        val broken = object : QueuePersistence by InMemoryPersistence() {
            override suspend fun loadAll(): List<QueueJob> = throw IllegalStateException("db closed")
        }
        val m = manager(broken)

        m.rehydrate()

        assertTrue(m.state.value.jobs.isEmpty())
        assertTrue("nothing may wait on a load that failed", m.rehydrated.value)
    }
}
