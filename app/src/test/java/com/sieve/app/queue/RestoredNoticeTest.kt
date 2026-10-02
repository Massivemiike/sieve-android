package com.sieve.app.queue

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import com.sieve.app.ui.queue.RestoredCopy
import com.sieve.app.ui.queue.showRestoredSnackbar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        val host = SnackbarHostState()
        var opened = 0
        launch { showRestoredSnackbar(host, 3) { opened++ } }
        runCurrent()

        val data = assertNotNull(host.currentSnackbarData)
        assertEquals("Restored 3 unfinished items — paused", data.visuals.message)
        assertEquals("View", data.visuals.actionLabel)
        assertEquals(SnackbarDuration.Long, data.visuals.duration, "not left up forever, but long enough to read and tap")
        assertEquals(0, opened)

        data.performAction()
        runCurrent()
        assertEquals(1, opened)
        assertNull(host.currentSnackbarData)
    }

    @Test fun dismissingTheSnackbarDoesNotNavigate() = runTest {
        val host = SnackbarHostState()
        var opened = 0
        launch { showRestoredSnackbar(host, 1) { opened++ } }
        runCurrent()

        host.currentSnackbarData!!.dismiss()
        runCurrent()

        assertEquals(0, opened)
        assertNull(host.currentSnackbarData)
    }
}
