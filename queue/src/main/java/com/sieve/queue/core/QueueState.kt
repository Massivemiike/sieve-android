package com.sieve.queue.core

data class QueueState(
    val jobs: List<QueueJob> = emptyList(),
    val globalPaused: Boolean = false,
    val maxDownloads: Int = 3,      // desktop default maxConcurrentDownloads
    val maxTranscodes: Int = 1,     // ConcurrencyPlanner default for HW encoder
    val retryPolicy: RetryPolicy = RetryPolicy(),
) {
    fun job(id: String) = jobs.firstOrNull { it.id == id }
}
