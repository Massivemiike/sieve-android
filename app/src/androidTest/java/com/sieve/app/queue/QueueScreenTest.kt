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
import com.sieve.queue.core.RestoreHold
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

    // --- the "Restored N unfinished items" banner (queue came back paused after an upgrade) -------------------

    private fun restoredState(hold: RestoreHold = RestoreHold(migrated = true, heldIds = setOf("a", "b"))) =
        QueueUiState.from(QueueState(jobs = listOf(job("a", DownloadStatus.PAUSED), job("b", DownloadStatus.PAUSED))), hold)

    @Test
    fun theRestoredBannerNamesTheCountAndItsButtonsFire() {
        var resumedAll = 0
        var dismissed = 0
        rule.setContent {
            SieveTheme {
                QueueScreen(
                    restoredState(), onPause = {}, onResume = {}, onRetry = {}, onCancel = {},
                    onResumeAllRestored = { resumedAll++ }, onDismissRestored = { dismissed++ },
                )
            }
        }

        rule.onNodeWithText("Restored 2 unfinished items").assertIsDisplayed()
        rule.onNodeWithText("They're paused. Nothing starts until you resume.").assertIsDisplayed()
        rule.onNodeWithTag("restore_resume_all").performClick()
        assertEquals(1, resumedAll)
        assertEquals(0, dismissed)
        rule.onNodeWithTag("restore_dismiss").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun noBannerWhenNothingIsHeldOrItWasDismissed() {
        rule.setContent { SieveTheme { QueueScreen(restoredState(RestoreHold.SETTLED), onPause = {}, onResume = {}, onRetry = {}, onCancel = {}) } }
        rule.onNodeWithTag("restore_banner").assertDoesNotExist()
    }

    @Test
    fun theBannerGoesAwayWhenTheHoldIsDismissedAndTheRowsStayListed() {
        val hold = MutableStateFlow(RestoreHold(migrated = true, heldIds = setOf("a", "b")))
        val vm = QueueViewModel(
            MutableStateFlow(QueueState(jobs = listOf(job("a", DownloadStatus.PAUSED), job("b", DownloadStatus.PAUSED)))),
            restoreSource = hold,
            onDismissRestore = { hold.value = hold.value.copy(bannerDismissed = true) },
        )
        rule.setContent { SieveTheme { QueueRoute(vm) } }
        rule.onNodeWithTag("restore_banner").assertIsDisplayed()

        rule.onNodeWithTag("restore_dismiss").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("restore_banner").assertDoesNotExist()
        rule.onNodeWithTag("job_a").assertIsDisplayed()   // dismissing only hides the banner
        rule.onNodeWithTag("job_b").assertIsDisplayed()
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
