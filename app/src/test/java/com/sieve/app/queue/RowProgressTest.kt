package com.sieve.app.queue

import com.sieve.app.ui.queue.RowProgress
import com.sieve.app.ui.queue.rowProgress
import com.sieve.queue.core.CancelReason
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSignal
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.Outcome
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.Phase
import com.sieve.queue.core.QueueEvent
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueReducer
import com.sieve.queue.core.QueueState
import com.sieve.queue.core.UnifiedProgress
import com.sieve.queue.persist.TaskMapping
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The progress strip of a Queue row: what it shows while running, and what a PAUSED row keeps of it. */
class RowProgressTest {

    private fun job(status: DownloadStatus, progress: UnifiedProgress = UnifiedProgress()) = QueueJob(
        id = "j", spec = JobSpec.Download("https://x/j", emptyList()), output = OutputRequest("Download/Sieve", "%(title)s.%(ext)s"),
        status = status, progress = progress, title = "Job j",
    )

    // --- a running row (as before) ----------------------------------------------------------------------------

    @Test fun aRunningRowShowsPercentSpeedAndTimeLeft() {
        val rp = rowProgress(job(DownloadStatus.RUNNING, UnifiedProgress(fraction = 0.63f, speed = "4.2MiB/s", eta = "00:41")))
        assertEquals(RowProgress(0.63f, "63% · 4.2MiB/s · 00:41 left"), rp)
    }

    @Test fun aRunningRowWithNoFractionYetIsIndeterminateWithADash() {
        assertEquals(RowProgress(null, "—"), rowProgress(job(DownloadStatus.RUNNING)))
        assertEquals(RowProgress(null, "— · 1.0MiB/s"), rowProgress(job(DownloadStatus.PREPARING, UnifiedProgress(speed = "1.0MiB/s"))))
    }

    // --- a paused row ------------------------------------------------------------------------------------------

    @Test fun aPausedRowShowsThePercentItHadReachedAndItsBar() {
        val rp = rowProgress(job(DownloadStatus.PAUSED, UnifiedProgress(fraction = 0.42f, phase = Phase.PAUSED)))
        assertEquals(RowProgress(0.42f, "42%"), rp)
    }

    @Test fun aPausedRowShowsNeitherSpeedNorTimeLeft() {
        // Even if the progress still carries them: a paused row is not moving, and its eta holds the word "paused".
        val rp = rowProgress(job(DownloadStatus.PAUSED, UnifiedProgress(fraction = 0.42f, speed = "1.0MiB/s", eta = "paused")))
        assertEquals("42%", assertNotNull(rp).meta)
    }

    @Test fun aPausedRowThatHadOnlyStartedShowsZeroPercent() {
        assertEquals(RowProgress(0f, "0%"), rowProgress(job(DownloadStatus.PAUSED, UnifiedProgress(fraction = 0f))))
    }

    // --- a paused transcode: ffmpeg cannot resume a partial output, so Resume starts it from 0 -------------------

    private fun transcode(status: DownloadStatus, progress: UnifiedProgress) = QueueJob(
        id = "t", spec = JobSpec.Transcode("/in/clip.mov", emptyList(), 60.0, usedHardwareEncoder = false),
        output = OutputRequest("Download/Sieve", "%(title)s.%(ext)s"), status = status, progress = progress, title = "Clip",
    )

    @Test fun aPausedTranscodeShowsNoPercentAndNoBarBecauseItRestartsFromZero() {
        val progress = UnifiedProgress(fraction = 0.42f, speed = "1.5x", eta = "paused", phase = Phase.PAUSED)
        assertNull(rowProgress(transcode(DownloadStatus.PAUSED, progress)))
        // the same progress on a download is kept: yt-dlp continues the partial file
        assertEquals(RowProgress(0.42f, "42%"), rowProgress(job(DownloadStatus.PAUSED, progress)))
    }

    @Test fun aRunningTranscodeStillShowsItsStrip() {
        val rp = rowProgress(transcode(DownloadStatus.RUNNING, UnifiedProgress(fraction = 0.42f, speed = "1.5x", eta = "00:20")))
        assertEquals(RowProgress(0.42f, "42% · 1.5x · 00:20 left"), rp)
    }

    @Test fun aTranscodePausedByTheUserShowsNoStripEvenThoughTheReducerKeepsItsFraction() {
        val running = transcode(DownloadStatus.RUNNING, UnifiedProgress(fraction = 0.4f, speed = "1.5x", eta = "00:30", phase = Phase.TRANSCODING))
            .copy(cancelReason = CancelReason.PAUSE)
        val paused = QueueReducer.reduce(
            QueueState(jobs = listOf(running)),
            QueueEvent.Signal(JobSignal.Terminal("t", Outcome.Cancelled(CancelReason.PAUSE))),
        ).job("t")!!

        assertEquals(DownloadStatus.PAUSED, paused.status)
        assertNotNull(paused.progress.fraction) // it is still in the state...
        assertNull(rowProgress(paused)) // ...but the row does not promise it
    }

    @Test fun aPausedRowWithNoKnownProgressShowsNoStrip() {
        assertNull(rowProgress(job(DownloadStatus.PAUSED, UnifiedProgress(eta = "paused", phase = Phase.PAUSED))))
    }

    // --- where a paused row's progress comes from --------------------------------------------------------------

    @Test fun aRowPausedByTheUserKeepsItsProgressForTheStrip() {
        val running = job(DownloadStatus.RUNNING, UnifiedProgress(fraction = 0.4f, speed = "2.0MiB/s", eta = "01:00", phase = Phase.DOWNLOADING))
            .copy(cancelReason = CancelReason.PAUSE)
        val paused = QueueReducer.reduce(
            QueueState(jobs = listOf(running)),
            QueueEvent.Signal(JobSignal.Terminal("j", Outcome.Cancelled(CancelReason.PAUSE))),
        ).job("j")!!

        assertEquals(DownloadStatus.PAUSED, paused.status)
        assertEquals(RowProgress(0.4f, "40%"), rowProgress(paused))
    }

    @Test fun aRowRestoredPausedFromTheDatabaseShowsNoStripNotAWrongNumber() {
        // Room keeps a row's progress as 0 or 1, so what the row had reached is gone after a restart.
        val running = job(DownloadStatus.RUNNING, UnifiedProgress(fraction = 0.4f, speed = "2.0MiB/s", eta = "01:00"))
        val saved = TaskMapping.toEntity(running)
        assertEquals(0f, saved.progress)

        val restored = QueueReducer.reduce(QueueState(jobs = listOf(TaskMapping.toDomain(saved))), QueueEvent.RehydrateHeld(setOf("j"))).job("j")!!

        assertEquals(DownloadStatus.PAUSED, restored.status)
        assertNull(rowProgress(restored))
    }

    // --- every other row shows none ----------------------------------------------------------------------------

    @Test fun rowsThatAreNeitherRunningNorPausedShowNoStrip() {
        val withProgress = UnifiedProgress(fraction = 0.9f, speed = "1.0MiB/s", eta = "00:01")
        for (s in listOf(DownloadStatus.QUEUED, DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)) {
            assertNull(rowProgress(job(s, withProgress)), "$s")
        }
    }
}
