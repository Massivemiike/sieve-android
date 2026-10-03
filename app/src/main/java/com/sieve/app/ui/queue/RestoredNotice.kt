package com.sieve.app.ui.queue

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.sieve.app.ui.common.AppSnackbars
import com.sieve.app.ui.nav.Dest
import com.sieve.app.ui.nav.NavRequests

/** The wording of the one-time "queue restored paused" notice: the Queue banner and the launch snackbar. */
object RestoredCopy {
    fun title(count: Int) = "Restored $count unfinished ${if (count == 1) "item" else "items"}"
    const val EXPLANATION = "They're paused. Nothing starts until you resume."
    fun snackbar(count: Int) = "${title(count)} — paused"
}

/**
 * Shows the launch snackbar for the rows the one-time restore brought back paused; [onView] runs if the user taps
 * View. Suspends until the snackbar is gone, so call it from its own coroutine.
 *
 * It stays until the user taps View or dismisses it: a notice that timed out after ten seconds went unseen by anyone
 * who was not looking at the phone at launch. A snackbar with no timeout would hold up every completion snackbar
 * queued behind it, so it is preemptible ([AppSnackbars.showPreemptible]): the next snackbar, or opening the Queue tab
 * ([CoverRestoredNoticeOnQueue]), takes it down.
 */
suspend fun showRestoredSnackbar(snackbars: AppSnackbars, count: Int, onView: () -> Unit) {
    val result = snackbars.showPreemptible(
        RestoredCopy.snackbar(count),
        actionLabel = "View",
        withDismissAction = true,
        duration = SnackbarDuration.Indefinite,
    )
    if (result == SnackbarResult.ActionPerformed) onView()
}

/**
 * The launch-time notice: [consumeNotice] is the queue's one-time flag (it returns the number of restored rows once, then
 * 0), so the snackbar appears once per restore, however often this runs. View opens the Queue tab.
 */
suspend fun announceRestoredItems(
    snackbars: AppSnackbars,
    consumeNotice: () -> Int,
    openQueue: () -> Unit = { NavRequests.open(Dest.QUEUE.route) },
) {
    val count = consumeNotice()
    if (count > 0) showRestoredSnackbar(snackbars, count, openQueue)
}

/**
 * The Queue tab's banner ("Restored N unfinished items", with Dismiss / Resume all) says what the launch snackbar says, so
 * the snackbar goes away when the Queue is opened, and is not shown at all while it is open.
 */
@Composable
fun CoverRestoredNoticeOnQueue(snackbars: AppSnackbars, currentRoute: String?) {
    LaunchedEffect(snackbars, currentRoute) { snackbars.setPreemptibleCovered(currentRoute == Dest.QUEUE.route) }
}
