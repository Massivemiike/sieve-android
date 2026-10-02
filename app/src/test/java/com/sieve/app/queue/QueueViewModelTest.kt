package com.sieve.app.queue

import com.sieve.app.ui.queue.QueueUiState
import com.sieve.app.ui.queue.QueueViewModel
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import com.sieve.queue.core.RestoreHold
import com.sieve.queue.core.UnifiedProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun job(id: String, status: DownloadStatus, pos: Long, frac: Float? = null) = QueueJob(
        id = id,
        spec = JobSpec.Download("https://x/$id", emptyList()),
        output = OutputRequest("Download/Sieve", "%(title)s.%(ext)s"),
        status = status,
        position = pos,
        progress = UnifiedProgress(fraction = frac, speed = "1.0MiB/s", eta = "00:10"),
        title = "Job $id",
    )

    @Test fun summarizesAndSortsByPosition() {
        val state = QueueState(
            jobs = listOf(
                job("a", DownloadStatus.RUNNING, pos = 2, frac = 0.5f),
                job("b", DownloadStatus.QUEUED, pos = 1),
                job("c", DownloadStatus.COMPLETED, pos = 0),
            ),
        )
        val ui = QueueUiState.from(state)
        assertEquals(1, ui.summary.running)
        assertEquals(1, ui.summary.queued)
        assertEquals(1, ui.summary.done)
        assertEquals(listOf("c", "b", "a"), ui.jobs.map { it.id })
    }

    @Test fun finishedCountsCompletedFailedAndCancelledRowsOnly() {
        val state = QueueState(
            jobs = listOf(
                job("q", DownloadStatus.QUEUED, 0), job("r", DownloadStatus.RUNNING, 1), job("p", DownloadStatus.PAUSED, 2),
                job("ok", DownloadStatus.COMPLETED, 3), job("bad", DownloadStatus.FAILED, 4), job("x", DownloadStatus.CANCELLED, 5),
            ),
        )
        assertEquals(3, QueueUiState.from(state).finished)
        assertEquals(0, QueueUiState.from(QueueState(jobs = listOf(job("q", DownloadStatus.QUEUED, 0)))).finished)
    }

    @Test fun removeAndClearFinishedDelegateToCallbacks() {
        val removed = mutableListOf<String>()
        var cleared = 0
        val vm = QueueViewModel(MutableStateFlow(QueueState()), onRemove = { removed += it }, onClearFinished = { cleared++ })
        vm.remove("a"); vm.remove("b"); vm.clearFinished()
        assertEquals(listOf("a", "b"), removed)
        assertEquals(1, cleared)
    }

    // --- the "Restored N unfinished items" banner ----------------------------------------------------------------

    private fun restored(vararg held: String) = RestoreHold(migrated = true, heldIds = held.toSet())

    @Test fun bannerCountsTheHeldRowsThatAreStillPaused() {
        val state = QueueState(
            jobs = listOf(
                job("a", DownloadStatus.PAUSED, 0), job("b", DownloadStatus.PAUSED, 1),
                job("moved", DownloadStatus.QUEUED, 2),   // was held, has been resumed since
                job("mine", DownloadStatus.PAUSED, 3),    // paused by the user: not held
            ),
        )
        val ui = QueueUiState.from(state, restored("a", "b", "moved", "gone"))
        assertEquals(2, ui.restoredHeld)
        assertTrue(ui.showRestoreBanner)
    }

    @Test fun bannerIsHiddenWhenNothingIsHeld() {
        val state = QueueState(jobs = listOf(job("a", DownloadStatus.PAUSED, 0)))
        assertEquals(0, QueueUiState.from(state, RestoreHold.SETTLED).restoredHeld)
        assertFalse(QueueUiState.from(state, RestoreHold.SETTLED).showRestoreBanner)
        assertFalse(QueueUiState.from(state).showRestoreBanner)   // the default: no hold at all
    }

    @Test fun bannerIsHiddenOnceDismissedButTheRowsStayListed() {
        val state = QueueState(jobs = listOf(job("a", DownloadStatus.PAUSED, 0), job("b", DownloadStatus.PAUSED, 1)))
        val ui = QueueUiState.from(state, restored("a", "b").copy(bannerDismissed = true))
        assertFalse(ui.showRestoreBanner)
        assertEquals(listOf("a", "b"), ui.jobs.map { it.id })
    }

    @Test fun bannerHidesWhenTheLastHeldRowIsResumed() = runTest {
        val queue = MutableStateFlow(QueueState(jobs = listOf(job("a", DownloadStatus.PAUSED, 0), job("b", DownloadStatus.PAUSED, 1))))
        val hold = MutableStateFlow(restored("a", "b"))
        val vm = QueueViewModel(queue, restoreSource = hold)
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()
        assertEquals(2, vm.state.value.restoredHeld)

        queue.value = queue.value.copy(jobs = queue.value.jobs.map { if (it.id == "a") it.copy(status = DownloadStatus.QUEUED) else it })
        hold.value = restored("b")
        runCurrent()
        assertEquals(1, vm.state.value.restoredHeld)

        queue.value = queue.value.copy(jobs = queue.value.jobs.map { it.copy(status = DownloadStatus.QUEUED) })
        hold.value = restored()
        runCurrent()
        assertEquals(0, vm.state.value.restoredHeld)
        assertFalse(vm.state.value.showRestoreBanner)
    }

    @Test fun bannerActionsDelegateToCallbacks() {
        var resumedAll = 0
        var dismissed = 0
        val vm = QueueViewModel(MutableStateFlow(QueueState()), onResumeHeld = { resumedAll++ }, onDismissRestore = { dismissed++ })
        vm.resumeAllHeld(); vm.dismissRestore(); vm.dismissRestore()
        assertEquals(1, resumedAll)
        assertEquals(2, dismissed)
    }

    @Test fun actionsDelegateToCallbacks() {
        val paused = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val vm = QueueViewModel(
            MutableStateFlow(QueueState()),
            onPause = { paused += it },
            onCancel = { cancelled += it },
        )
        vm.pause("x"); vm.cancel("y")
        assertEquals(listOf("x"), paused)
        assertEquals(listOf("y"), cancelled)
    }
}
