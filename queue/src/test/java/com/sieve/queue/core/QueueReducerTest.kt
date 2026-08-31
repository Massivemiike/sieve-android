package com.sieve.queue.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueReducerTest {
    private fun dl(id: String, s: DownloadStatus = DownloadStatus.QUEUED, attempt: Int = 0) =
        QueueJob(id, JobSpec.Download("u$id", listOf("-f", "best")), OutputRequest("d", "o"), status = s, attempt = attempt)
    private fun tx(id: String, s: DownloadStatus = DownloadStatus.QUEUED) =
        QueueJob(id, JobSpec.Transcode("/in.mkv", listOf("-c:v", "h264"), 10.0, true), OutputRequest("d", "o.mp4"), status = s)
    private fun r(state: QueueState, vararg ev: QueueEvent) = ev.fold(state) { st, e -> QueueReducer.reduce(st, e) }

    @Test fun `enqueue appends with monotonic position and QUEUED`() {
        val s = r(QueueState(), QueueEvent.Enqueue(dl("a")), QueueEvent.Enqueue(dl("b")))
        assertEquals(listOf("a", "b"), s.jobs.map { it.id })
        assertTrue(s.jobs[0].position < s.jobs[1].position)
        assertTrue(s.jobs.all { it.status == DownloadStatus.QUEUED })
    }

    @Test fun `mark preparing then running`() {
        val s = r(QueueState(jobs = listOf(dl("a"))), QueueEvent.MarkPreparing(listOf("a")), QueueEvent.MarkRunning("a"))
        assertEquals(DownloadStatus.RUNNING, s.job("a")!!.status)
    }

    @Test fun `progress signal updates progress and pushes speed history`() {
        val s = r(
            QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING))),
            QueueEvent.Signal(JobSignal.Progress("a", UnifiedProgress(fraction = 0.5f, speed = "2.0MiB/s", phase = Phase.DOWNLOADING))),
        )
        val j = s.job("a")!!
        assertEquals(0.5f, j.progress.fraction!!, 1e-4f)
        assertEquals(listOf(2.0f), j.speedHistory)
    }

    @Test fun `speed history is capped at 30`() {
        var s = QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING)))
        repeat(35) { s = QueueReducer.reduce(s, QueueEvent.Signal(JobSignal.Progress("a", UnifiedProgress(speed = "1.0MiB/s")))) }
        assertEquals(30, s.job("a")!!.speedHistory.size)
    }

    @Test fun `postprocess log moves running download's phase without leaving RUNNING`() {
        val s = r(
            QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING))),
            QueueEvent.Signal(JobSignal.Log("a", "[Merger] Merging formats into out.mkv", isError = false)),
        )
        val j = s.job("a")!!
        assertEquals(DownloadStatus.RUNNING, j.status)
        assertEquals(Phase.POSTPROCESS, j.progress.phase)
    }

    @Test fun `log with filePath captures filePath`() {
        val s = r(
            QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING))),
            QueueEvent.Signal(JobSignal.Log("a", "[download] Destination: /x/out.mp4", isError = false, filePath = "/x/out.mp4")),
        )
        assertEquals("/x/out.mp4", s.job("a")!!.filePath)
    }

    @Test fun `terminal Succeeded goes COMPLETED with fraction 1 and completedAt`() {
        val s = r(QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING))), QueueEvent.Signal(JobSignal.Terminal("a", Outcome.Succeeded)))
        val j = s.job("a")!!
        assertEquals(DownloadStatus.COMPLETED, j.status)
        assertEquals(1.0f, j.progress.fraction!!, 1e-4f)
        assertNotNull(j.completedAt)
    }

    @Test fun `terminal Failed transient below cap stays QUEUED for auto-retry with attempt bumped`() {
        val s = r(
            QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING))),
            QueueEvent.Signal(JobSignal.Terminal("a", Outcome.Failed(FailureInfo("HTTP Error 429")))),
        )
        val j = s.job("a")!!
        assertEquals(DownloadStatus.QUEUED, j.status)
        assertEquals(1, j.attempt)
    }

    @Test fun `terminal Failed transient at cap goes FAILED`() {
        val s = r(
            QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING, attempt = 1))),
            QueueEvent.Signal(JobSignal.Terminal("a", Outcome.Failed(FailureInfo("timed out")))),
        )
        assertEquals(DownloadStatus.FAILED, s.job("a")!!.status)
    }

    @Test fun `terminal Failed permanent goes FAILED immediately`() {
        val s = r(
            QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING))),
            QueueEvent.Signal(JobSignal.Terminal("a", Outcome.Failed(FailureInfo("HTTP Error 403")))),
        )
        val j = s.job("a")!!
        assertEquals(DownloadStatus.FAILED, j.status)
        assertEquals("HTTP Error 403", j.error)
    }

    @Test fun `terminal Cancelled with PAUSE reason goes PAUSED and resets speed eta`() {
        val running = dl("a", DownloadStatus.RUNNING).copy(
            progress = UnifiedProgress(fraction = 0.4f, speed = "2.0MiB/s", eta = "01:00"),
            cancelReason = CancelReason.PAUSE,
        )
        val s = r(QueueState(jobs = listOf(running)), QueueEvent.Signal(JobSignal.Terminal("a", Outcome.Cancelled(CancelReason.PAUSE))))
        val j = s.job("a")!!
        assertEquals(DownloadStatus.PAUSED, j.status)
        assertEquals("paused", j.progress.eta)
        assertNull(j.progress.speed)
        assertEquals(0.4f, j.progress.fraction!!, 1e-4f)
        assertNull(j.cancelReason)
    }

    @Test fun `terminal Cancelled with USER_CANCEL reason goes CANCELLED`() {
        val s = r(
            QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING).copy(cancelReason = CancelReason.USER_CANCEL))),
            QueueEvent.Signal(JobSignal.Terminal("a", Outcome.Cancelled(CancelReason.USER_CANCEL))),
        )
        assertEquals(DownloadStatus.CANCELLED, s.job("a")!!.status)
    }

    @Test fun `terminal Cancelled with SHUTDOWN goes QUEUED for resume`() {
        val s = r(
            QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING).copy(cancelReason = CancelReason.SHUTDOWN))),
            QueueEvent.Signal(JobSignal.Terminal("a", Outcome.Cancelled(CancelReason.SHUTDOWN))),
        )
        assertEquals(DownloadStatus.QUEUED, s.job("a")!!.status)
    }

    @Test fun `pause stamps cancelReason PAUSE on a running job`() {
        val s = r(QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING))), QueueEvent.Pause("a"))
        assertEquals(CancelReason.PAUSE, s.job("a")!!.cancelReason)
        assertEquals(DownloadStatus.RUNNING, s.job("a")!!.status)
    }

    @Test fun `resume on paused download flips to QUEUED (drained with -c later)`() {
        val s = r(QueueState(jobs = listOf(dl("a", DownloadStatus.PAUSED))), QueueEvent.Resume("a"))
        assertEquals(DownloadStatus.QUEUED, s.job("a")!!.status)
        assertNull(s.job("a")!!.progress.speed)
    }

    @Test fun `cancel a queued job removes nothing but marks CANCELLED`() {
        val s = r(QueueState(jobs = listOf(dl("a", DownloadStatus.QUEUED))), QueueEvent.Cancel("a"))
        assertEquals(DownloadStatus.CANCELLED, s.job("a")!!.status)
    }

    @Test fun `cancel a running job stamps USER_CANCEL (terminal completes it)`() {
        val s = r(QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING))), QueueEvent.Cancel("a"))
        assertEquals(CancelReason.USER_CANCEL, s.job("a")!!.cancelReason)
    }

    @Test fun `manual retry resets an error row to QUEUED and bumps attempt`() {
        val s = r(
            QueueState(jobs = listOf(dl("a", DownloadStatus.FAILED, attempt = 1).copy(error = "boom"))),
            QueueEvent.Retry("a"),
        )
        val j = s.job("a")!!
        assertEquals(DownloadStatus.QUEUED, j.status)
        assertNull(j.error)
        assertEquals(2, j.attempt)
        assertEquals(0f, j.progress.fraction ?: 0f, 1e-4f)
    }

    @Test fun `remove deletes the row`() {
        val s = r(QueueState(jobs = listOf(dl("a"), dl("b"))), QueueEvent.Remove("a"))
        assertEquals(listOf("b"), s.jobs.map { it.id })
    }

    @Test fun `rehydrate reverts in-flight to QUEUED, leaves terminal alone`() {
        val s = r(
            QueueState(
                jobs = listOf(
                    dl("a", DownloadStatus.RUNNING), dl("b", DownloadStatus.PREPARING),
                    dl("c", DownloadStatus.PAUSED), tx("d", DownloadStatus.COMPLETED), dl("e", DownloadStatus.FAILED),
                ),
            ),
            QueueEvent.Rehydrate,
        )
        assertEquals(DownloadStatus.QUEUED, s.job("a")!!.status)
        assertEquals(DownloadStatus.QUEUED, s.job("b")!!.status)
        assertEquals(DownloadStatus.QUEUED, s.job("c")!!.status)
        assertEquals(DownloadStatus.COMPLETED, s.job("d")!!.status)
        assertEquals(DownloadStatus.FAILED, s.job("e")!!.status)
    }

    @Test fun `reorder moves before target`() {
        val s = r(QueueState(jobs = listOf(dl("a"), dl("b"), dl("c"))), QueueEvent.Reorder("c", "a"))
        assertEquals(listOf("c", "a", "b"), orderedIds(s))
    }

    private fun orderedIds(s: QueueState) = s.jobs.sortedBy { it.position }.map { it.id }

    @Test fun `late signal after terminal is ignored`() {
        val s = r(
            QueueState(jobs = listOf(dl("a", DownloadStatus.COMPLETED))),
            QueueEvent.Signal(JobSignal.Progress("a", UnifiedProgress(fraction = 0.2f))),
        )
        assertEquals(1.0f, s.job("a")!!.progress.fraction ?: 1f, 1e-4f)
    }
}
