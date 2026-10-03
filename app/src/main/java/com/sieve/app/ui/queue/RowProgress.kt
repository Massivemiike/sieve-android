package com.sieve.app.ui.queue

import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.QueueJob

/** What a Queue row's progress strip shows: the bar ([fraction]; null = indeterminate) and the line under it. */
internal data class RowProgress(val fraction: Float?, val meta: String)

/**
 * The progress strip for [job], or null when its row shows none.
 *
 * A running row shows the bar, the percent, the speed and the time left. A paused DOWNLOAD shows the bar and the percent
 * it had reached (the Windows app does the same), but no speed or time left: it is not moving. That percent is only
 * known for the session in which the row was paused. Room keeps progress as 0 or 1 (see `DownloadTaskEntity`), so a
 * row restored from the database has none, and then the strip is left out rather than showing a made-up 0%.
 *
 * A paused TRANSCODE shows no strip at all: ffmpeg cannot resume a partial output ([QueueJob.resumable]), so Resume starts
 * it again from 0 and the percent it had reached is not progress that is kept. The percent is for work that continues.
 */
internal fun rowProgress(job: QueueJob): RowProgress? {
    val p = job.progress
    return when (job.status) {
        DownloadStatus.RUNNING, DownloadStatus.PREPARING -> RowProgress(
            p.fraction,
            listOfNotNull(p.fraction?.let(::percentLabel) ?: "—", p.speed, p.eta?.let { "$it left" }).joinToString(" · "),
        )
        DownloadStatus.PAUSED -> if (job.resumable) p.fraction?.let { RowProgress(it, percentLabel(it)) } else null
        else -> null
    }
}

private fun percentLabel(fraction: Float) = "${(fraction * 100).toInt()}%"
