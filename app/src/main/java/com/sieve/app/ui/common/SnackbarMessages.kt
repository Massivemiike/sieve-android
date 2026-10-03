package com.sieve.app.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** A message for the app-wide snackbar, with at most one action button ([onAction] runs when it is tapped). */
data class SnackbarMessage(
    val text: String,
    val actionLabel: String? = null,
    val onAction: () -> Unit = {},
)

/**
 * A mailbox from anywhere in the UI layer (a screen, a callback that outlives the Activity) to the app-wide snackbar
 * ([rememberAppSnackbars] collects it), for the screens that sit outside any Scaffold of their own. Same shape as
 * [com.sieve.app.ui.nav.NavRequests] and [SharedUrlBus]: a process-wide object. A message posted before the host is
 * composing waits in the buffer; past [BUFFER] the oldest is dropped.
 */
object SnackbarMessages {
    internal const val BUFFER = 8

    private val mailbox = Channel<SnackbarMessage>(capacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val flow: Flow<SnackbarMessage> = mailbox.receiveAsFlow()

    fun post(message: SnackbarMessage) {
        mailbox.trySend(message)
    }
}

/**
 * Shows [message] and runs its action if the user taps it. Suspends until the snackbar is gone, so call it from its own
 * coroutine. A snackbar with an action times out like any other (Long, not Indefinite: only [AppSnackbars.showPreemptible]
 * may stay up, because it gives way to every snackbar queued behind it).
 */
suspend fun AppSnackbars.showMessage(message: SnackbarMessage) {
    val result = show(
        message.text,
        actionLabel = message.actionLabel,
        duration = if (message.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
    )
    if (result == SnackbarResult.ActionPerformed) message.onAction()
}
