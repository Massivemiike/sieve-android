package com.sieve.queue.service

import com.sieve.engine.parse.YtdlpErrors
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobKind
import com.sieve.queue.core.QueueJob

/**
 * The in-app (snackbar) wording for a finished job. It is the desktop toast's text, and a finished
 * job's title is the system notification's own ([QueueNotification.renderDone]), so the snackbar and
 * the notification can never disagree about what happened:
 *  - "Downloaded: <title>" / "Transcoded: <title>"
 *  - "Download failed: <reason>" / "Transcode failed: <title> — <reason>"
 */
object JobToast {
    /** Null for any status that isn't a finished outcome (cancelled, paused and queued rows stay quiet). */
    fun text(job: QueueJob): String? = when (job.status) {
        DownloadStatus.COMPLETED -> QueueNotification.renderDone(job)?.title
        DownloadStatus.FAILED -> {
            val reason = job.error?.takeIf { it.isNotBlank() }?.let { YtdlpErrors.format(YtdlpErrors.humanize(it)) }
            if (job.kind == JobKind.TRANSCODE) {
                val name = job.title.ifBlank { "file" }
                if (reason == null) "Transcode failed: $name" else "Transcode failed: $name — $reason"
            } else {
                if (reason == null) "Download failed" else "Download failed: $reason"
            }
        }
        else -> null
    }
}
