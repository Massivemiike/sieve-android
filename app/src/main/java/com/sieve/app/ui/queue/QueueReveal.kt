package com.sieve.app.ui.queue

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A process-wide hand-off for "show the user the row they just added". The queue is oldest-first, so a new row is appended
 * below the fold once the list is longer than one screen, and the user could not see that their download had started.
 * The code that enqueues on the user's behalf (the Download and Transcode screens) [request]s the new job's id; the Queue
 * screen brings that row into view, once, and then [consume]s the request. Like [com.sieve.app.ui.nav.NavRequests] it is a
 * plain object, so a request made while the Queue tab is not on screen waits for the next time it is, and it is never
 * written to disk: a new process starts with nothing pending.
 */
object QueueReveal {
    private val _id = MutableStateFlow<String?>(null)

    /** The job whose row still has to be shown, or null. */
    val id: StateFlow<String?> = _id.asStateFlow()

    /** The user just added [jobId]. The newest request wins: a batch of adds reveals its last row. */
    fun request(jobId: String) { _id.value = jobId }

    /** The row [jobId] was shown (or the user took over the scrolling). A newer request, for another row, is left alone. */
    fun consume(jobId: String) { _id.compareAndSet(jobId, null) }
}
