package com.sieve.app.queue

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.sieve.app.ui.queue.QueueScreen
import com.sieve.app.ui.queue.QueueUiState
import com.sieve.app.ui.theme.SieveTheme
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.Phase
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import com.sieve.queue.core.UnifiedProgress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a PAUSED Queue row says about its progress, on the real row: a download continues from the partial file, so it keeps its bar
 * and percent; an ffmpeg transcode cannot resume a partial output (`QueueJob.resumable`), starts again from 0 on Resume, and so
 * shows neither.
 */
@RunWith(RobolectricTestRunner::class)
class PausedRowProgressTest {
    @get:Rule val rule = createComposeRule()

    private val reached = UnifiedProgress(fraction = 0.42f, speed = "1.0MiB/s", eta = "paused", phase = Phase.PAUSED)

    private fun download(id: String, status: DownloadStatus) = QueueJob(
        id, JobSpec.Download("https://x/$id", emptyList()), OutputRequest("Downloads/Sieve", "%(title)s.%(ext)s"),
        status = status, progress = reached, title = "Download $id",
    )

    private fun transcode(id: String, status: DownloadStatus, progress: UnifiedProgress = reached) = QueueJob(
        id, JobSpec.Transcode("/in/$id.mov", emptyList(), 60.0, usedHardwareEncoder = false), OutputRequest("Downloads/Sieve", "%(title)s.%(ext)s"),
        status = status, progress = progress, title = "Transcode $id",
    )

    private fun showQueueOf(vararg jobs: QueueJob) {
        rule.setContent { SieveTheme { QueueScreen(QueueUiState.from(QueueState(jobs = jobs.toList())), {}, {}, {}, {}) } }
    }

    @Test fun aPausedDownloadKeepsItsBarAndPercentAndAPausedTranscodeShowsNeither() {
        showQueueOf(download("dl", DownloadStatus.PAUSED), transcode("tx", DownloadStatus.PAUSED))

        rule.onNodeWithTag("job_dl").assertIsDisplayed()
        rule.onNodeWithTag("bar_dl").assertIsDisplayed()
        rule.onNodeWithTag("progress_dl").assertTextEquals("42%")

        rule.onNodeWithTag("job_tx").assertIsDisplayed()
        rule.onNodeWithTag("resume_tx").assertIsDisplayed() // Resume is still offered
        rule.onNodeWithTag("bar_tx").assertDoesNotExist()
        rule.onNodeWithTag("progress_tx").assertDoesNotExist()
    }

    @Test fun aRunningTranscodeStillShowsItsBarAndPercent() {
        showQueueOf(transcode("tx", DownloadStatus.RUNNING, UnifiedProgress(fraction = 0.42f, speed = "1.5x", eta = "00:20")))

        rule.onNodeWithTag("bar_tx").assertIsDisplayed()
        rule.onNodeWithTag("progress_tx").assertTextEquals("42% · 1.5x · 00:20 left")
    }
}
