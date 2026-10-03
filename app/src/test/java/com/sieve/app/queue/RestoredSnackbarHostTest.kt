package com.sieve.app.queue

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sieve.app.ui.common.AppSnackbarHost
import com.sieve.app.ui.common.AppSnackbars
import com.sieve.app.ui.nav.NavRequests
import com.sieve.app.ui.queue.announceRestoredItems
import com.sieve.app.ui.theme.SieveTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The launch snackbar "Restored N unfinished items — paused" in the app's real snackbar slot ([AppSnackbarHost]), with a
 * virtual clock: it used to time out after about ten seconds, before anyone who was not looking at the phone at launch
 * could notice it.
 */
@RunWith(RobolectricTestRunner::class)
class RestoredSnackbarHostTest {
    @get:Rule val rule = createComposeRule()

    private val restoredText = "Restored 3 unfinished items — paused"
    private val snackbars = AppSnackbars()
    private val route = mutableStateOf<String?>("download")
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        NavRequests.consume()
        rule.setContent {
            scope = rememberCoroutineScope()
            SieveTheme { Box { AppSnackbarHost(snackbars, route.value) } }
        }
    }

    @After fun tearDown() = NavRequests.consume()

    private fun announce(restored: Int = 3, flag: AtomicInteger = AtomicInteger(restored)) {
        rule.runOnIdle { scope.launch { announceRestoredItems(snackbars, { flag.getAndSet(0) }) } }
        rule.waitForIdle()
    }

    private fun requestCompletionSnackbar(text: String = "Downloaded: Cats") {
        rule.runOnIdle { scope.launch { snackbars.show(text) } }
    }

    @Test fun staysUpWellPastTheOldTenSecondTimeout() {
        announce()
        rule.onNodeWithText(restoredText).assertIsDisplayed()

        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(15_000)

        rule.onNodeWithText(restoredText).assertIsDisplayed()
        rule.onNodeWithText("View").assertIsDisplayed()
        rule.onNodeWithContentDescription("Dismiss").assertIsDisplayed()
    }

    @Test fun isAnnouncedToTalkBackThroughTheSnackbarsLiveRegion() {
        announce()
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        assertTrue(rule.onAllNodes(polite).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun tappingViewOpensTheQueueAndTakesTheSnackbarDown() {
        announce()
        rule.onNodeWithText("View").performClick()
        rule.waitForIdle()

        assertEquals("queue", NavRequests.route.value)
        rule.onNodeWithText(restoredText).assertDoesNotExist()
    }

    @Test fun theDismissButtonTakesItDownWithoutNavigating() {
        announce()
        rule.onNodeWithContentDescription("Dismiss").performClick()
        rule.waitForIdle()

        assertEquals(null, NavRequests.route.value)
        rule.onNodeWithText(restoredText).assertDoesNotExist()
    }

    @Test fun aCompletionSnackbarRequestedWhileItIsShownAppearsPromptlyInsteadOfWaitingForIt() {
        announce()
        rule.mainClock.autoAdvance = false

        requestCompletionSnackbar()
        rule.mainClock.advanceTimeBy(500)

        rule.onNodeWithText("Downloaded: Cats").assertIsDisplayed()
        rule.onNodeWithText(restoredText).assertDoesNotExist()
        assertEquals(null, NavRequests.route.value, "View is not acted on for a snackbar that was preempted")
    }

    @Test fun aCompletionSnackbarStillTimesOutOnItsOwn() {
        announce()
        rule.mainClock.autoAdvance = false
        requestCompletionSnackbar()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Downloaded: Cats").assertIsDisplayed()

        rule.mainClock.advanceTimeBy(6_000)

        rule.onNodeWithText("Downloaded: Cats").assertDoesNotExist()
        rule.onNodeWithText(restoredText).assertDoesNotExist()
    }

    @Test fun openingTheQueueTabTakesItDown() {
        announce()
        rule.onNodeWithText(restoredText).assertIsDisplayed()

        rule.runOnIdle { route.value = "queue" }
        rule.waitForIdle()

        rule.onNodeWithText(restoredText).assertDoesNotExist()
    }

    @Test fun isNotShownAtAllWhileTheQueueTabIsOpen() {
        rule.runOnIdle { route.value = "queue" }
        rule.waitForIdle()

        announce()

        rule.onNodeWithText(restoredText).assertDoesNotExist()
    }

    @Test fun appearsOnlyOnceForTheOneTimeFlag() {
        val flag = AtomicInteger(3)
        announce(flag = flag)
        rule.onNodeWithText(restoredText).assertIsDisplayed()
        rule.onNodeWithContentDescription("Dismiss").performClick()
        rule.waitForIdle()

        announce(flag = flag) // a second launch-time pass finds the flag already used
        announce(flag = flag)

        rule.onNodeWithText(restoredText).assertDoesNotExist()
        rule.runOnIdle { assertNull(snackbars.state.currentSnackbarData, "no snackbar at all, of any wording") }
    }
}
