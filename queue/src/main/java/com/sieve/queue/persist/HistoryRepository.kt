package com.sieve.queue.persist

import com.sieve.data.dao.HistoryDao
import com.sieve.data.db.DownloadHistoryEntity
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.QueueJob

/** Completion write-path. Desktop writes history on successful completion only. */
class HistoryRepository(private val dao: HistoryDao) {
    suspend fun recordCompletion(job: QueueJob, now: Long) {
        dao.insertAndTrim(
            DownloadHistoryEntity(
                url = (job.spec as? JobSpec.Download)?.url ?: "",
                title = job.title, channel = job.channel, site = job.site, format = job.format,
                sizeBytes = job.progress.sizeBytes, filePath = job.filePath, thumbnailUrl = job.thumbnailUrl,
                durationSec = job.durationSec, downloadedAt = now,
            ),
        )
    }
}
