package com.sieve.app.queue

import androidx.compose.material3.SnackbarDuration
import com.sieve.app.ui.common.AppSnackbars
import com.sieve.app.ui.queue.RestoredCopy
import com.sieve.app.ui.queue.announceRestoredItems
import com.sieve.app.ui.queue.showRestoredSnackbar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RestoredNoticeTest {

    @Test fun titleNamesTheCountAndAgreesInNumber() {
        assertEquals("Restored 1 unfinished item", RestoredCopy.title(1))
        assertEquals("Restored 2 unfinished items", RestoredCopy.title(2))
        assertEquals("Restored 12 unfinished items", RestoredCopy.title(12))
    }

    @Test fun snackbarSaysTheyAreStillPaused() {
        assertEquals("Restored 3 unfinished items — paused", RestoredCopy.snackbar(3))
    }

    @Test fun snackbarOffersViewAndTheTapOpensTheQueue() = runTest {
        val snackbars = AppSnackbars()
        var opened = 0
        launch { showRestoredSnackbar(snackbars, 3) { opened++ } }
        runCurrent()

        val data = assertNotNull(snackbars.state.currentSnackbarData)
        assertEquals("Restored 3 unfinished items — paused", data.visuals.message)
        assertEquals("View", data.visuals.actionLabel)
        assertEquals(0, opened)

        data.performAction()
        runCurrent()
        assertEquals(1, opened)
        assertNull(snackbars.state.currentSnackbarData)
    }

    @Test fun snackbarStaysUntilTheUserActsAndCanBeDismissed() = runTest {
        // Its look: no timeout, and a close button. TalkBack announces it through the snackbar's own live region.
        val snackbars = AppSnackbars()
        launch { showRestoredSnackbar(snackbars, 3) {} }
        runCurrent()

        val visuals = assertNotNull(snackbars.state.currentSnackbarData).visuals
        assertEquals(SnackbarDuration.Indefinite, visuals.duration)
        assertTrue(visuals.withDismissAction)

        snackbars.state.currentSnackbarData!!.dismiss()
        runCurrent()
    }

    @Test fun dismissingTheSnackbarDoesNotNavigate() = runTest {
        val snackbars = AppSnackbars()
        var opened = 0
        launch { showRestoredSnackbar(snackbars, 1) { opened++ } }
        runCurrent()

        snackbars.state.currentSnackbarData!!.dismiss()
        runCurrent()

        assertEquals(0, opened)
        assertNull(snackbars.state.currentSnackbarData)
    }

    @Test fun theLaunchNoticeAppearsOnceForTheOneTimeFlag() = runTest {
        val snackbars = AppSnackbars()
        val flag = AtomicInteger(3) // the queue's consumeRestoreNotice(): the count once, then 0
        val shown = mutableListOf<String>()
        repeat(3) {
            launch { announceRestoredItems(snackbars, { flag.getAndSet(0) }) {} }
            runCurrent()
            snackbars.state.currentSnackbarData?.let { shown += it.visuals.message; it.dismiss() }
            runCurrent()
        }
        assertEquals(listOf("Restored 3 unfinished items — paused"), shown)
    }

    @Test fun noRestoredRowsMeansNoSnackbar() = runTest {
        val snackbars = AppSnackbars()
        launch { announceRestoredItems(snackbars, { 0 }) {} }
        runCurrent()
        assertNull(snackbars.state.currentSnackbarData)
    }
}
