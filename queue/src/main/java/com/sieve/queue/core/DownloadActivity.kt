package com.sieve.queue.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * True while any yt-dlp DOWNLOAD job is PREPARING or RUNNING. Gate for engine self-updates: the
 * youtubedl-android updater deletes and recreates the yt-dlp directory in place, which must never
 * happen underneath a live yt-dlp process. Transcodes don't count (ffmpeg is a separate binary the
 * updater doesn't touch); QUEUED/PAUSED rows aren't running anything.
 */
fun QueueState.hasActiveDownload(): Boolean = jobs.any {
    it.kind == JobKind.DOWNLOAD && (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PREPARING)
}

/**
 * Suspends until no download is active, then re-checks after [settleMs] — a rehydrate or a retry
 * backoff can admit a job right after the queue first looks idle. Returns false if [timeoutMs]
 * elapses before the queue is idle (the caller should skip the update, not force it).
 */
suspend fun StateFlow<QueueState>.awaitNoActiveDownload(timeoutMs: Long, settleMs: Long = 0L): Boolean =
    withTimeoutOrNull(timeoutMs) {
        do {
            first { !it.hasActiveDownload() }
            if (settleMs > 0) delay(settleMs)
        } while (value.hasActiveDownload())
        true
    } ?: false
