package com.sieve.queue.core

/** Valid concurrency caps, shared with the Settings steppers. */
object QueueLimits {
    val DOWNLOADS = 1..10
    val TRANSCODES = 1..4
}

data class QueueState(
    val jobs: List<QueueJob> = emptyList(),
    val globalPaused: Boolean = false,
    val maxDownloads: Int = 3,      // desktop default maxConcurrentDownloads
    val maxTranscodes: Int = 1,     // ConcurrencyPlanner default for HW encoder
    val retryPolicy: RetryPolicy = RetryPolicy(),
) {
    fun job(id: String) = jobs.firstOrNull { it.id == id }
}
