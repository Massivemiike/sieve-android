package com.sieve.queue.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadActivityTest {
    private fun dl(id: String, status: DownloadStatus) =
        QueueJob(id, JobSpec.Download("u", emptyList()), OutputRequest("d", "o"), status = status)

    private fun tx(id: String, status: DownloadStatus) =
        QueueJob(id, JobSpec.Transcode("/in", emptyList(), null, false), OutputRequest("d", "o"), status = status)

    private fun state(vararg jobs: QueueJob) = QueueState(jobs = jobs.toList())

    @Test fun `empty queue is idle`() = assertFalse(QueueState().hasActiveDownload())

    @Test fun `running download is active`() =
        assertTrue(state(dl("a", DownloadStatus.RUNNING)).hasActiveDownload())

    @Test fun `preparing download is active`() =
        assertTrue(state(dl("a", DownloadStatus.PREPARING)).hasActiveDownload())

    @Test fun `any one active download among idle rows is enough`() =
        assertTrue(
            state(
                dl("a", DownloadStatus.COMPLETED), dl("b", DownloadStatus.QUEUED), dl("c", DownloadStatus.RUNNING),
            ).hasActiveDownload(),
        )

    @Test fun `queued paused and terminal downloads are idle`() =
        assertFalse(
            state(
                dl("a", DownloadStatus.QUEUED), dl("b", DownloadStatus.PAUSED), dl("c", DownloadStatus.COMPLETED),
                dl("d", DownloadStatus.FAILED), dl("e", DownloadStatus.CANCELLED),
            ).hasActiveDownload(),
        )

    @Test fun `running transcode alone does not block an update`() =
        assertFalse(state(tx("a", DownloadStatus.RUNNING), tx("b", DownloadStatus.PREPARING)).hasActiveDownload())

    // ---- awaitNoActiveDownload ---------------------------------------------------------------

    private val active = state(dl("a", DownloadStatus.RUNNING))
    private val idle = state(dl("a", DownloadStatus.COMPLETED))

    @Test fun `await returns true immediately when already idle`() = runTest {
        assertTrue(MutableStateFlow(QueueState()).awaitNoActiveDownload(timeoutMs = 1_000))
    }

    @Test fun `await suspends while a download runs and resumes when it finishes`() = runTest {
        val flow = MutableStateFlow(active)
        val result = async { flow.awaitNoActiveDownload(timeoutMs = 60_000) }
        runCurrent()
        assertFalse(result.isCompleted)
        flow.value = idle
        runCurrent()
        assertTrue(result.await())
    }

    @Test fun `await gives up and returns false when downloads never finish`() = runTest {
        val flow = MutableStateFlow(active)
        assertFalse(flow.awaitNoActiveDownload(timeoutMs = 7_200_000))
    }

    @Test fun `await keeps waiting if a job starts during the settle window`() = runTest {
        val flow = MutableStateFlow(idle)
        val result = async { flow.awaitNoActiveDownload(timeoutMs = 60_000, settleMs = 5_000) }
        runCurrent()
        advanceTimeBy(2_000)
        flow.value = active            // rehydrate / retry admits a job just after "idle"
        advanceTimeBy(4_000)           // settle elapsed -> sees active -> waits again
        runCurrent()
        assertFalse(result.isCompleted)
        flow.value = idle
        assertTrue(result.await())
    }
}
