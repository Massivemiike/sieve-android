package com.sieve.app.ui.queue

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult

/** The wording of the one-time "queue restored paused" notice: the Queue banner and the launch snackbar. */
object RestoredCopy {
    fun title(count: Int) = "Restored $count unfinished ${if (count == 1) "item" else "items"}"
    const val EXPLANATION = "They're paused. Nothing starts until you resume."
    fun snackbar(count: Int) = "${title(count)} — paused"
}

/**
 * Shows the launch snackbar for the rows the one-time restore brought back paused; [onView] runs if the user taps
 * View. Suspends until the snackbar is gone, so call it from its own coroutine.
 */
suspend fun showRestoredSnackbar(host: SnackbarHostState, count: Int, onView: () -> Unit) {
    // Long, not the default: a snackbar with an action never times out by itself, and would hold up every
    // completion snackbar queued behind it.
    val result = host.showSnackbar(RestoredCopy.snackbar(count), actionLabel = "View", duration = SnackbarDuration.Long)
    if (result == SnackbarResult.ActionPerformed) onView()
}
