package com.sieve.queue.service

import com.sieve.engine.repo.EngineEvent
import com.sieve.queue.core.ArgReconciler
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueManagerRetryTest {
    private fun dl(id: String) = QueueJob(id, JobSpec.Download("u", listOf("-f", "best")), OutputRequest("d", "o"))

    @Test fun `transient failure auto-retries after 5s and then succeeds`() = runTest {
        var attempt = 0
        val port = FakeDownloadPort {
            flow {
                attempt++
                if (attempt == 1) emit(EngineEvent.Failed("Network timed out")) // transient → retry
                else emit(EngineEvent.Completed(0))
            }
        }
        // Clock backed by virtual time so the 5 s backoff advances with the test scheduler.
        val clock = object : Clock { override fun nowMs() = testScheduler.currentTime }
        val m = QueueManager(
            JobDriver(port, FakeTranscodePort()), port, FakeTranscodePort(),
            InMemoryPersistence(), FakeOutputProvider(), clock, initial = QueueState(maxDownloads = 1),
        )
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(DownloadStatus.QUEUED, m.state.value.job("a")!!.status) // waiting for backoff
        assertEquals(1, m.state.value.job("a")!!.attempt)

        advanceTimeBy(5_001)
        m.state.first { it.job("a")?.status == DownloadStatus.COMPLETED }
        assertEquals(DownloadStatus.COMPLETED, m.state.value.job("a")!!.status)
        assertEquals(2, attempt)
    }

    @Test fun `completion invokes history callback exactly once`() = runTest {
        val recorded = mutableListOf<String>()
        val port = FakeDownloadPort { flow { emit(EngineEvent.Completed(0)) } }
        val m = QueueManager(
            JobDriver(port, FakeTranscodePort()), port, FakeTranscodePort(),
            InMemoryPersistence(), FakeOutputProvider(), FakeClock(),
            initial = QueueState(maxDownloads = 1), onCompleted = { recorded += it.id },
        )
        m.start(backgroundScope)
        m.enqueue(dl("a"))
        m.state.first { it.job("a")?.status == DownloadStatus.COMPLETED }
        assertEquals(listOf("a"), recorded)
    }

    @Test fun `reconcileQueuedArgs rewrites only queued rows`() = runTest {
        val port = FakeDownloadPort { flow { awaitCancellation() } }
        val m = QueueManager(
            JobDriver(port, FakeTranscodePort()), port, FakeTranscodePort(),
            InMemoryPersistence(), FakeOutputProvider(), FakeClock(), initial = QueueState(maxDownloads = 0), // 0 = nothing drains
        )
        m.start(backgroundScope)
        m.enqueue(dl("a")); m.enqueue(dl("b"))
        m.reconcileQueuedArgs { ArgReconciler.rewriteFlagValue(it, "-N", "4") }
        val a = m.state.value.job("a")!!.spec as JobSpec.Download
        assertTrue(a.engineArgs.containsAll(listOf("-N", "4")))
    }
}
