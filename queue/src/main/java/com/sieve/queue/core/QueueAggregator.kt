package com.sieve.queue.core

data class QueueSummary(
    val running: Int,
    val queued: Int,
    val done: Int,
    val failed: Int,
    val total: Int,
    val totalSpeedMiB: Float,
    val remainingBytes: Long,
    val activeTitle: String?,
    val overallFraction: Float?,   // mean of running fractions, null if none determinate
) {
    /** Service stopSelf predicate: no running and no queued work left. */
    val isIdle: Boolean get() = running == 0 && queued == 0
}

object QueueAggregator {
    fun summarize(jobs: List<QueueJob>): QueueSummary {
        val running = jobs.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PREPARING }
        val queued = jobs.count { it.status == DownloadStatus.QUEUED }
        val done = jobs.count { it.status == DownloadStatus.COMPLETED }
        val failed = jobs.count { it.status == DownloadStatus.FAILED }

        val totalSpeed = jobs.filter { it.status == DownloadStatus.RUNNING }
            .sumOf { parseSpeedMiB(it.progress.speed).toDouble() }.toFloat()

        val remaining = jobs.filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PAUSED }
            .sumOf { j ->
                val size = j.progress.sizeBytes ?: return@sumOf 0L
                val frac = j.progress.fraction ?: 0f
                (size * (1f - frac).coerceAtLeast(0f)).toLong()
            }

        val activeTitle = jobs.firstOrNull { it.status == DownloadStatus.RUNNING }?.title?.takeIf { it.isNotBlank() }

        val runningFracs = jobs.filter { it.status == DownloadStatus.RUNNING }.mapNotNull { it.progress.fraction }
        val overall = if (runningFracs.isEmpty()) null else runningFracs.average().toFloat()

        return QueueSummary(running, queued, done, failed, jobs.size, totalSpeed, remaining, activeTitle, overall)
    }
}
