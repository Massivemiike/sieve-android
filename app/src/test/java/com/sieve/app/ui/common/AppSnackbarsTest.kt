package com.sieve.app.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule every snackbar in :app lives by: the one that may stay up (the restored-items notice) never holds up another.
 * No host is composed here; the host's own timeout is covered by [com.sieve.app.queue.RestoredSnackbarHostTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppSnackbarsTest {

    private fun AppSnackbars.now(): String? = state.currentSnackbarData?.visuals?.message

    /** Lets every queued snackbar through, so [runTest] has nothing left waiting. */
    private fun TestScope.drain(s: AppSnackbars) {
        repeat(10) {
            s.state.currentSnackbarData?.dismiss()
            runCurrent()
        }
    }

    private fun TestScope.launchPreemptible(s: AppSnackbars, text: String, into: MutableList<SnackbarResult>) = launch {
        into += s.showPreemptible(text, "View", withDismissAction = true, duration = SnackbarDuration.Indefinite)
    }

    @Test fun `a snackbar requested while the preemptible one is showing replaces it at once`() = runTest {
        val s = AppSnackbars()
        val results = mutableListOf<SnackbarResult>()
        launchPreemptible(s, "restored", results)
        runCurrent()
        assertEquals("restored", s.now())

        launch { s.show("Downloaded: Cats") }
        runCurrent()

        assertEquals("Downloaded: Cats", s.now())
        assertEquals(listOf(SnackbarResult.Dismissed), results, "the preempted one reports Dismissed, so its View is not acted on")
        drain(s)
    }

    @Test fun `a preemptible snackbar still queued behind another is never shown once a snackbar is requested`() = runTest {
        val s = AppSnackbars()
        val results = mutableListOf<SnackbarResult>()
        launch { s.show("a") }
        runCurrent()
        launchPreemptible(s, "restored", results)
        runCurrent()
        assertEquals("a", s.now())
        launch { s.show("b") }
        runCurrent()
        assertEquals(listOf(SnackbarResult.Dismissed), results)

        s.state.currentSnackbarData!!.dismiss()
        runCurrent()
        assertEquals("b", s.now(), "b is next, not the restored notice")
        drain(s)
    }

    @Test fun `snackbars that time out by themselves still queue one behind another`() = runTest {
        val s = AppSnackbars()
        launch { s.show("a") }
        launch { s.show("b") }
        runCurrent()
        assertEquals("a", s.now())

        s.state.currentSnackbarData!!.dismiss()
        runCurrent()
        assertEquals("b", s.now())
        drain(s)
    }

    @Test fun `a snackbar requested when no preemptible one exists shows normally`() = runTest {
        val s = AppSnackbars()
        launch { s.show("Can't open this file") }
        runCurrent()
        assertEquals("Can't open this file", s.now())
        drain(s)
    }

    @Test fun `covering the preemptible snackbar takes it down, and while covered it is not shown`() = runTest {
        val s = AppSnackbars()
        val results = mutableListOf<SnackbarResult>()
        launchPreemptible(s, "restored", results)
        runCurrent()
        assertEquals("restored", s.now())

        s.setPreemptibleCovered(true)
        runCurrent()
        assertNull(s.now())
        assertEquals(listOf(SnackbarResult.Dismissed), results)

        launchPreemptible(s, "restored again", results)
        runCurrent()
        assertNull(s.now(), "not shown over the screen that already says it")
        assertEquals(2, results.size)

        s.setPreemptibleCovered(false)
        launchPreemptible(s, "restored a third time", results)
        runCurrent()
        assertEquals("restored a third time", s.now())
        drain(s)
    }

    @Test fun `covering does not touch an ordinary snackbar`() = runTest {
        val s = AppSnackbars()
        launch { s.show("Downloaded: Cats") }
        runCurrent()
        s.setPreemptibleCovered(true)
        runCurrent()
        assertEquals("Downloaded: Cats", s.now())
        drain(s)
    }

    @Test fun `a newer preemptible snackbar replaces the older one`() = runTest {
        val s = AppSnackbars()
        val results = mutableListOf<SnackbarResult>()
        launchPreemptible(s, "first", results)
        runCurrent()
        launchPreemptible(s, "second", results)
        runCurrent()
        assertEquals("second", s.now())
        assertEquals(listOf(SnackbarResult.Dismissed), results)
        drain(s)
    }

    @Test fun `the user's tap on the action is reported, a dismissal is not`() = runTest {
        val s = AppSnackbars()
        val results = mutableListOf<SnackbarResult>()
        launchPreemptible(s, "one", results)
        runCurrent()
        s.state.currentSnackbarData!!.performAction()
        runCurrent()
        launchPreemptible(s, "two", results)
        runCurrent()
        s.state.currentSnackbarData!!.dismiss()
        runCurrent()
        assertEquals(listOf(SnackbarResult.ActionPerformed, SnackbarResult.Dismissed), results)
    }

    @Test fun `cancelling the caller of a preemptible snackbar cancels the caller and clears the snackbar`() = runTest {
        val s = AppSnackbars()
        val job = launch { s.showPreemptible("restored", "View", withDismissAction = true, duration = SnackbarDuration.Indefinite) }
        runCurrent()
        assertEquals("restored", s.now())

        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled, "a preemption must not swallow the caller's own cancellation")
        assertNull(s.now())
    }
}
