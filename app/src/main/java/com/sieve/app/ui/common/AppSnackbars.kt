package com.sieve.app.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive

/**
 * The one door every snackbar in :app goes through (a test fails when a call site goes round it), so that one rule holds
 * for all of them: a snackbar that stays up until the user acts, [showPreemptible], never holds up another.
 *
 * A [SnackbarHostState] shows one snackbar at a time and queues the rest behind it, so a snackbar with no timeout would
 * keep every completion snackbar waiting for as long as the user ignores it. That one is therefore preemptible: [show]
 * takes it down first (whether it is on screen or still queued behind another), and so does [setPreemptibleCovered] when
 * the user opens the screen that already says the same thing. There is at most one at a time.
 *
 * Main thread only, like the host it wraps.
 */
class AppSnackbars(val state: SnackbarHostState = SnackbarHostState()) {

    private var preemptible: Deferred<SnackbarResult>? = null
    private var covered = false

    /**
     * Shows a snackbar that times out by itself ([duration]; never Indefinite: use [showPreemptible]) and suspends until it
     * is gone. It queues behind other such snackbars, but not behind a preemptible one, which it dismisses first.
     */
    suspend fun show(
        message: String,
        actionLabel: String? = null,
        duration: SnackbarDuration = SnackbarDuration.Short,
    ): SnackbarResult {
        preemptible?.cancel()
        return state.showSnackbar(message, actionLabel, withDismissAction = false, duration = duration)
    }

    /**
     * Shows a snackbar that is allowed to stay up until the user taps its action or dismisses it ([duration] Indefinite),
     * and suspends until it is gone. It is [SnackbarResult.Dismissed] when [show] or [setPreemptibleCovered] took it down
     * (and when it was never shown because its screen was already covered), so the caller does not act on it. If the
     * caller itself is cancelled that propagates, as always.
     */
    suspend fun showPreemptible(
        message: String,
        actionLabel: String?,
        withDismissAction: Boolean,
        duration: SnackbarDuration,
    ): SnackbarResult {
        if (covered) return SnackbarResult.Dismissed
        return coroutineScope {
            val shown = async { state.showSnackbar(message, actionLabel, withDismissAction, duration) }
            preemptible?.cancel()
            preemptible = shown
            try {
                shown.await()
            } catch (e: CancellationException) {
                // Only `shown` was cancelled (a preemption): this snackbar is gone, the caller is fine. If the caller was
                // cancelled too, that is its own cancellation to propagate.
                if (!isActive) throw e
                SnackbarResult.Dismissed
            } finally {
                if (preemptible === shown) preemptible = null
            }
        }
    }

    /**
     * The user is (not) looking at the screen that already carries the preemptible snackbar's message. While [covered], the
     * one on screen (or waiting) is taken down and a new one is not shown.
     */
    fun setPreemptibleCovered(covered: Boolean) {
        this.covered = covered
        if (covered) preemptible?.cancel()
    }
}
