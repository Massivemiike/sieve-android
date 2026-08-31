package com.sieve.queue.core

/**
 * The persistence seam the pure [com.sieve.queue.core] layer depends on, so the QueueManager stays
 * Android-free. The Room-backed implementation lives in `com.sieve.queue.persist` (which may touch
 * Room/`:data`); this interface never does.
 */
interface QueuePersistence {
    suspend fun loadAll(): List<QueueJob>
    suspend fun upsert(job: QueueJob)
    suspend fun upsertAll(jobs: List<QueueJob>)
    suspend fun updateStatus(id: String, status: DownloadStatus)
    suspend fun delete(id: String)
    suspend fun prune(cutoff: Long): Int
}
