package com.sieve.app.ui.nav

import android.content.Intent
import com.sieve.queue.service.QueueNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A process-wide hand-off for "go to this screen" requests that come from outside the UI (a tapped
 * failure notification). MainActivity publishes; [SieveNavHost] navigates and consumes.
 */
object NavRequests {
    private val _route = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = _route.asStateFlow()

    fun open(route: String) { _route.value = route }
    fun consume() { _route.value = null }

    /** The screen a launch / notification intent asks for, or null. Pure — unit-testable. */
    fun routeFor(openQueue: Boolean): String? = if (openQueue) Dest.QUEUE.route else null

    fun routeFor(intent: Intent?): String? =
        routeFor(intent?.getBooleanExtra(QueueNotification.EXTRA_OPEN_QUEUE, false) == true)
}
