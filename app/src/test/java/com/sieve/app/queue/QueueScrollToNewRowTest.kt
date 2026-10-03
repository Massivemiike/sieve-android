package com.sieve.app.queue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.sieve.app.ui.queue.QueueReveal
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Queue list is oldest-first, so a download the user just added is appended below the fold once the list is longer than
 * one screen, and they could not see that it started. The Queue tab now scrolls that row into view, once, and never fights the
 * user's own scrolling. 30 rows on a 360x640dp phone is about six screens, so the new row is far off screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class QueueScrollToNewRowTest {

    @get:Rule val rule = createComposeRule()

    @Before @After fun clearTheProcessWideRequest() { QueueReveal.id.value?.let(QueueReveal::consume) }

    private fun job(n: Int, status: DownloadStatus = DownloadStatus.COMPLETED, fraction: Float? = null) = QueueJob(
        id = "j$n", spec = JobSpec.Download("https://x/j$n", emptyList()), output = OutputRequest("Download/Sieve", "%(title)s.%(ext)s"),
        status = status, position = n.toLong(), title = "Job $n",
        progress = UnifiedProgress(fraction = fraction),
    )

    /** The queue after thirty adds: oldest first, the last one is j30. */
    private fun thirty() = (1..30).map { job(it) }

    private fun fresh(n: Int = 31) = job(n, DownloadStatus.QUEUED)

    /** What QueueRoute does with the process-wide request, with the request held by the test instead. */
    private class Host(jobs: List<QueueJob>) {
        var jobs by mutableStateOf(jobs)
        var reveal by mutableStateOf<String?>(null)
        val handled = mutableListOf<String>()

        @Composable fun Content() {
            QueueScreen(
                state = QueueUiState.from(QueueState(jobs = jobs)),
                onPause = {}, onResume = {}, onRetry = {}, onCancel = {},
                revealJobId = reveal,
                onRevealed = { handled += it; if (reveal == it) reveal = null },
            )
        }
    }

    private fun show(host: Host) = rule.setContent { SieveTheme { host.Content() } }

    /** The user adds a download: the queue gains the row and the screen is asked to show it. */
    private fun add(host: Host, row: QueueJob) = rule.runOnIdle {
        host.jobs = host.jobs + row
        host.reveal = row.id
    }

    private fun assertFullyInTheList(tag: String) {
        val list = rule.onNodeWithTag("queue_list").fetchSemanticsNode().boundsInRoot
        val row = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue(row.top >= list.top && row.bottom <= list.bottom, "$tag $row is not inside the list $list")
    }

    private fun assertListIsAtTheTop() {
        rule.onNodeWithTag("job_j1").assertIsDisplayed()
        rule.onNodeWithTag("job_j2").assertIsDisplayed()
    }

    @Test fun addingADownloadToALongQueueBringsItsRowIntoView() {
        val host = Host(thirty())
        show(host)
        assertListIsAtTheTop()
        rule.onNodeWithTag("job_j31").assertDoesNotExist()

        add(host, fresh())
        rule.waitForIdle()

        rule.onNodeWithTag("job_j31").assertIsDisplayed()
        assertFullyInTheList("job_j31")
        assertEquals(listOf("j31"), host.handled)
        assertNull(host.reveal)
    }

    @Test fun theOrderStaysOldestFirstSoTheNewRowIsBelowTheOneBeforeIt() {
        val host = Host(thirty())
        show(host)

        add(host, fresh())
        rule.waitForIdle()

        val before = rule.onNodeWithTag("job_j30").fetchSemanticsNode().boundsInRoot
        val added = rule.onNodeWithTag("job_j31").fetchSemanticsNode().boundsInRoot
        assertTrue(before.bottom <= added.top, "j30 $before should sit above the new row $added")
    }

    @Test fun aRowThatWasScrolledPastBeforeTheAddIsStillRevealed() {
        val host = Host(thirty())
        show(host)
        rule.onNodeWithTag("queue_list").performScrollToIndex(10)
        rule.waitForIdle()
        rule.onNodeWithTag("job_j31").assertDoesNotExist()

        add(host, fresh())
        rule.waitForIdle()

        assertFullyInTheList("job_j31")
    }

    @Test fun theRowIsRevealedEvenWhenTheQueueLandsItAfterTheRequest() {
        val host = Host(thirty())
        show(host)

        rule.runOnIdle { host.reveal = "j31" } // the tap happened; the queue has not appended the row yet
        rule.waitForIdle()
        rule.onNodeWithTag("job_j31").assertDoesNotExist()
        assertTrue(host.handled.isEmpty(), "the request must wait for its row")

        rule.runOnIdle { host.jobs = host.jobs + fresh() }
        rule.waitForIdle()

        assertFullyInTheList("job_j31")
        assertEquals(listOf("j31"), host.handled)
    }

    @Test fun aShortQueueNeedsNoScrollAndStillClosesTheRequest() {
        val host = Host((1..3).map { job(it) })
        show(host)

        add(host, job(4, DownloadStatus.QUEUED))
        rule.waitForIdle()

        rule.onNodeWithTag("job_j1").assertIsDisplayed()
        assertFullyInTheList("job_j4")
        assertEquals(listOf("j4"), host.handled)
    }

    @Test fun theFirstDownloadEverAddedShowsUpOnTheEmptyQueue() {
        val host = Host(emptyList())
        show(host)

        add(host, job(1, DownloadStatus.QUEUED))
        rule.waitForIdle()

        assertFullyInTheList("job_j1")
        assertEquals(listOf("j1"), host.handled)
    }

    @Test fun aScrollByTheUserBeforeTheRowArrivesIsNotOverridden() {
        val host = Host(thirty())
        show(host)
        rule.runOnIdle { host.reveal = "j31" }
        rule.waitForIdle()

        // the user takes the list somewhere else while the add is still on its way
        rule.onNodeWithTag("queue_list").performScrollToIndex(10)
        rule.waitForIdle()
        rule.runOnIdle { host.jobs = host.jobs + fresh() }
        rule.waitForIdle()

        rule.onNodeWithTag("job_j11").assertIsDisplayed()
        rule.onNodeWithTag("job_j31").assertDoesNotExist()
        assertEquals(listOf("j31"), host.handled, "the request is closed, not left to fire later")
    }

    @Test fun aScrollByTheUserWhileTheListIsMovingToTheRowWins() {
        val host = Host(thirty())
        show(host)
        rule.mainClock.autoAdvance = false

        add(host, fresh())
        rule.mainClock.advanceTimeBy(120) // the animation toward the new row is under way, not finished
        rule.onNodeWithTag("queue_list").performTouchInput { swipeDown(startY = top + 8f, endY = bottom - 8f, durationMillis = 100) }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        assertListIsAtTheTop()
        rule.onNodeWithTag("job_j31").assertDoesNotExist()
        assertEquals(listOf("j31"), host.handled, "the pre-empted request is closed, not retried")
    }

    @Test fun afterTheRevealTheUsersOwnScrollAndLaterQueueUpdatesAreLeftAlone() {
        val host = Host(thirty())
        show(host)
        add(host, fresh())
        rule.waitForIdle()
        assertFullyInTheList("job_j31")

        rule.onNodeWithTag("queue_list").performScrollToIndex(0)
        rule.waitForIdle()
        assertListIsAtTheTop()

        // progress ticks rebuild every row's state, and a row the user did not add (a restored one, say) arrives
        rule.runOnIdle { host.jobs = host.jobs.map { it.copy(progress = UnifiedProgress(fraction = 0.5f)) } }
        rule.waitForIdle()
        rule.runOnIdle { host.jobs = host.jobs + job(32, DownloadStatus.PAUSED) }
        rule.waitForIdle()

        assertListIsAtTheTop()
        rule.onNodeWithTag("job_j32").assertDoesNotExist()
        assertEquals(listOf("j31"), host.handled)
    }

    @Test fun aRowThatArrivesWithNoRequestNeverMovesTheList() {
        val host = Host(thirty())
        show(host)

        rule.runOnIdle { host.jobs = host.jobs + fresh() } // not an add by the user: no request
        rule.waitForIdle()

        assertListIsAtTheTop()
        rule.onNodeWithTag("job_j31").assertDoesNotExist()
    }

    @Test fun aLongQueueOpenedWithNothingRequestedStaysAtTheTop() {
        // cold start, a restart of the process, a new visit to the tab: the queue is just there, nothing was added
        val host = Host(thirty() + fresh())
        show(host)

        assertListIsAtTheTop()
        rule.onNodeWithTag("job_j31").assertDoesNotExist()
        assertTrue(host.handled.isEmpty())
    }

    @Test fun aConfigurationChangeAfterTheRevealDoesNotScrollToTheOldRowAgain() {
        val host = Host(thirty())
        val restoration = StateRestorationTester(rule)
        restoration.setContent { SieveTheme { host.Content() } }
        add(host, fresh())
        rule.waitForIdle()
        assertFullyInTheList("job_j31")
        rule.onNodeWithTag("queue_list").performScrollToIndex(0)
        rule.waitForIdle()
        assertListIsAtTheTop()

        restoration.emulateSavedInstanceStateRestore() // rotation: the screen is rebuilt, the saved scroll position comes back
        rule.waitForIdle()

        assertListIsAtTheTop()
        rule.onNodeWithTag("job_j31").assertDoesNotExist()
        assertEquals(listOf("j31"), host.handled)
    }

    @Test fun theRouteTakesTheRequestFromQueueRevealShowsTheRowAndClearsIt() {
        val source = MutableStateFlow(QueueState(jobs = thirty()))
        val vm = QueueViewModel(source)
        rule.setContent { SieveTheme { QueueRoute(vm) } }
        assertListIsAtTheTop()

        // what the Download and Transcode screens do through their enqueue seam
        source.value = QueueState(jobs = source.value.jobs + fresh())
        QueueReveal.request("j31")
        rule.waitForIdle()

        assertFullyInTheList("job_j31")
        assertNull(QueueReveal.id.value)
    }

    @Test fun aRequestMadeWhileTheQueueTabIsNotOnScreenIsServedWhenItAppears() {
        val source = MutableStateFlow(QueueState(jobs = thirty() + fresh()))
        QueueReveal.request("j31") // the Download tab enqueued; the Queue tab has never been composed yet
        val vm = QueueViewModel(source)

        rule.setContent { SieveTheme { QueueRoute(vm) } }
        rule.waitForIdle()

        assertFullyInTheList("job_j31")
        assertNull(QueueReveal.id.value)
    }
}
