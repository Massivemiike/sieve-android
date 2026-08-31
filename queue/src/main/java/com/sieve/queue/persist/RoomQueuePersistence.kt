package com.sieve.queue.persist

import com.sieve.data.dao.QueueDao
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueuePersistence

/** Room-backed [QueuePersistence]. Bridges the domain [QueueJob] to [DownloadTaskEntity] via [TaskMapping]. */
class RoomQueuePersistence(private val dao: QueueDao) : QueuePersistence {
    override suspend fun loadAll(): List<QueueJob> = dao.getAll().map(TaskMapping::toDomain)
    override suspend fun upsert(job: QueueJob) = dao.upsert(TaskMapping.toEntity(job))
    // Loop single-row upserts instead of the list @Upsert: Room's generated list transaction
    // (manual beginTransaction/endTransaction) throws "no current transaction" under coroutine
    // dispatch hopping. Per-row upsert atomicity is sufficient here (rows are independent).
    override suspend fun upsertAll(jobs: List<QueueJob>) = jobs.forEach { dao.upsert(TaskMapping.toEntity(it)) }
    override suspend fun updateStatus(id: String, status: DownloadStatus) = dao.updateStatus(id, status.name)
    override suspend fun delete(id: String) = dao.deleteById(id)
    override suspend fun prune(cutoff: Long): Int = dao.prune(cutoff)
}
