package com.sieve.app.queue

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sieve.app.ui.queue.QueueRoute
import com.sieve.app.ui.queue.QueueScreen
import com.sieve.app.ui.queue.QueueUiState
import com.sieve.app.ui.queue.QueueViewModel
import com.sieve.app.ui.theme.SieveTheme
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import com.sieve.queue.core.UnifiedProgress
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class QueueScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun rendersRunningJobAndPauseFires() {
        val paused = mutableListOf<String>()
        val job = QueueJob(
            id = "j1",
            spec = JobSpec.Download("https://x/j1", emptyList()),
            output = OutputRequest("Download/Sieve", "%(title)s.%(ext)s"),
            status = DownloadStatus.RUNNING,
            progress = UnifiedProgress(fraction = 0.63f, speed = "4.2MiB/s", eta = "00:41"),
            title = "Blender Open Movie",
        )
        val vm = QueueViewModel(MutableStateFlow(QueueState(jobs = listOf(job))), onPause = { paused += it })

        rule.setContent { SieveTheme { QueueRoute(vm) } }

        rule.onNodeWithText("Blender Open Movie").assertIsDisplayed()
        rule.onNodeWithText("63% · 4.2MiB/s · 00:41 left").assertIsDisplayed()
        rule.onNodeWithTag("pause_j1").performClick()
        assertEquals(listOf("j1"), paused)
    }

    private fun job(id: String, status: DownloadStatus, error: String? = null) = QueueJob(
        id = id, spec = JobSpec.Download("https://x/$id", emptyList()), output = OutputRequest("Download/Sieve", "%(title)s.%(ext)s"),
        status = status, title = "Job $id", error = error,
    )

    @Test
    fun theXOnAFailedRowRemovesIt() {
        val removed = mutableListOf<String>()
        val vm = QueueViewModel(
            MutableStateFlow(QueueState(jobs = listOf(job("f", DownloadStatus.FAILED, "ERROR: boom")))),
            onRemove = { removed += it },
        )
        rule.setContent { SieveTheme { QueueRoute(vm) } }

        rule.onNodeWithTag("remove_f").performClick()
        assertEquals(listOf("f"), removed)
    }

    @Test
    fun clearFinishedAsksFirstThenClears() {
        var cleared = 0
        val vm = QueueViewModel(
            MutableStateFlow(
                QueueState(jobs = listOf(job("done", DownloadStatus.COMPLETED), job("bad", DownloadStatus.FAILED), job("run", DownloadStatus.RUNNING))),
            ),
            onClearFinished = { cleared++ },
        )
        rule.setContent { SieveTheme { QueueRoute(vm) } }

        rule.onNodeWithTag("clear_finished").assertIsEnabled().performClick()
        assertEquals(0, cleared)                                  // nothing yet: it confirms first
        rule.onNodeWithText("Removes 2 finished items from the queue. Saved files stay where they are.").assertIsDisplayed()
        rule.onNodeWithTag("clear_finished_confirm").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun clearFinishedIsDisabledWhenNothingIsFinished() {
        val vm = QueueViewModel(MutableStateFlow(QueueState(jobs = listOf(job("run", DownloadStatus.RUNNING)))))
        rule.setContent { SieveTheme { QueueRoute(vm) } }
        rule.onNodeWithTag("clear_finished").assertIsNotEnabled()
    }

    @Test
    fun theOpenButtonOnACompletedRowReportsTheJob() {
        val opened = mutableListOf<String>()
        val state = QueueUiState.from(QueueState(jobs = listOf(job("done", DownloadStatus.COMPLETED).copy(filePath = "content://media/external/downloads/7"))))
        rule.setContent {
            SieveTheme {
                QueueScreen(state, onPause = {}, onResume = {}, onRetry = {}, onCancel = {}, onOpen = { opened += it.id })
            }
        }
        rule.onNodeWithTag("open_done").performClick()
        assertEquals(listOf("done"), opened)
    }
}
