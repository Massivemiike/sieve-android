package com.sieve.queue.core

/**
 * Pure scheduler. Returns the ids to move QUEUED → PREPARING this pass — the analog of desktop
 * `startNextInQueue`, split per [JobKind], with an atomic claim (PREPARING already occupies a
 * permit) so a claimed-but-not-yet-running job cannot be double-admitted. Run order is `position`;
 * `pinned` is a view-only float and does NOT affect it.
 */
object NextItemSelector {
    fun select(state: QueueState): List<String> {
        if (state.globalPaused) return emptyList()

        fun occupied(kind: JobKind) = state.jobs.count {
            it.kind == kind && (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PREPARING)
        }

        var dlFree = (state.maxDownloads - occupied(JobKind.DOWNLOAD)).coerceAtLeast(0)
        var txFree = (state.maxTranscodes - occupied(JobKind.TRANSCODE)).coerceAtLeast(0)

        val admitted = mutableListOf<String>()
        for (job in state.jobs.filter { it.status == DownloadStatus.QUEUED }.sortedBy { it.position }) {
            when (job.kind) {
                JobKind.DOWNLOAD -> if (dlFree > 0) { admitted += job.id; dlFree-- }
                JobKind.TRANSCODE -> if (txFree > 0) { admitted += job.id; txFree-- }
            }
        }
        return admitted
    }
}
