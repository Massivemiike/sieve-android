package com.sieve.queue.core

private val POSTPROCESS_RE = Regex("""\[(Merger|ExtractAudio|EmbedThumbnail|VideoConvertor|FixupM3u8)]""")
private val FILEPATH_RE = Regex("""(?:Destination:|Merging formats into|has already been downloaded)\s*"?([^"\n]+\.[A-Za-z0-9]+)"?""")

/**
 * Pure, deterministic `reduce(state, event) -> state`. Encodes every queue transition. It does NOT
 * start work — it marks jobs PREPARING/RUNNING only when the selection pass (Task 6) tells it a slot
 * is free (via MarkPreparing/MarkRunning). Post-terminal signals are dropped. Uses an injectable
 * [NOW] clock so `completedAt` is deterministic in tests (Task 12 sets it to a real clock).
 */
object QueueReducer {
    fun reduce(state: QueueState, event: QueueEvent): QueueState = when (event) {
        is QueueEvent.Enqueue -> {
            val pos = (state.jobs.maxOfOrNull { it.position } ?: 0L) + 1L
            state.copy(jobs = state.jobs + event.job.copy(status = DownloadStatus.QUEUED, position = pos))
        }
        is QueueEvent.MarkPreparing -> mapJobs(state, event.ids.toSet()) { it.copy(status = DownloadStatus.PREPARING) }
        is QueueEvent.MarkRunning -> mapJob(state, event.id) {
            if (it.status == DownloadStatus.PREPARING) it.copy(status = DownloadStatus.RUNNING) else it
        }
        is QueueEvent.Signal -> applySignal(state, event.signal)
        is QueueEvent.Pause -> mapJob(state, event.id) {
            if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PREPARING)
                it.copy(cancelReason = CancelReason.PAUSE) else it
        }
        is QueueEvent.Cancel -> mapJob(state, event.id) {
            when (it.status) {
                DownloadStatus.QUEUED, DownloadStatus.PAUSED ->
                    it.copy(status = DownloadStatus.CANCELLED, completedAt = it.completedAt ?: NOW())
                DownloadStatus.RUNNING, DownloadStatus.PREPARING ->
                    it.copy(cancelReason = CancelReason.USER_CANCEL)
                else -> it
            }
        }
        is QueueEvent.Resume -> mapJob(state, event.id) {
            if (it.status == DownloadStatus.PAUSED)
                it.copy(status = DownloadStatus.QUEUED, progress = it.progress.copy(speed = null, eta = null)) else it
        }
        is QueueEvent.Retry -> mapJob(state, event.id) {
            if (it.status == DownloadStatus.FAILED)
                it.copy(
                    status = DownloadStatus.QUEUED, error = null, attempt = it.attempt + 1,
                    completedAt = null, cancelReason = null, progress = UnifiedProgress(phase = Phase.QUEUED),
                ) else it
        }
        is QueueEvent.AutoRetryFired -> state   // pure no-op; the manager re-drains on this event
        is QueueEvent.Remove -> state.copy(jobs = state.jobs.filterNot { it.id == event.id })
        is QueueEvent.SetGlobalPaused -> state.copy(globalPaused = event.paused)
        is QueueEvent.SetMaxDownloads -> state.copy(maxDownloads = event.n.coerceAtLeast(1))
        is QueueEvent.SetPinned -> mapJob(state, event.id) { it.copy(pinned = event.pinned) }
        is QueueEvent.Reorder -> reorder(state, event.id, event.beforeId)
        QueueEvent.Rehydrate -> state.copy(
            jobs = state.jobs.map {
                if (it.status in NON_TERMINAL_INFLIGHT)
                    it.copy(
                        status = DownloadStatus.QUEUED, cancelReason = null,
                        progress = it.progress.copy(speed = null, eta = null),
                    )
                else it
            },
        )
    }

    private fun applySignal(state: QueueState, signal: JobSignal): QueueState {
        val job = state.job(signal.jobId) ?: return state
        if (job.status.isTerminal) return state   // drop post-terminal signals
        return when (signal) {
            is JobSignal.Progress -> mapJob(state, job.id) {
                val hist = (it.speedHistory + parseSpeedMiB(signal.progress.speed))
                    .filter { s -> s > 0f }.takeLast(30)
                it.copy(progress = signal.progress, speedHistory = hist)
            }
            is JobSignal.Log -> mapJob(state, job.id) { j ->
                val newPhase = if (POSTPROCESS_RE.containsMatchIn(signal.line)) Phase.POSTPROCESS else j.progress.phase
                val fp = signal.filePath ?: FILEPATH_RE.find(signal.line)?.groupValues?.get(1) ?: j.filePath
                j.copy(
                    progress = j.progress.copy(phase = newPhase), filePath = fp,
                    logsTail = (j.logsTail + signal.line.take(500)).takeLast(200),
                )
            }
            is JobSignal.Terminal -> reduceTerminal(state, job, signal.outcome)
        }
    }

    private fun reduceTerminal(state: QueueState, job: QueueJob, outcome: Outcome): QueueState =
        mapJob(state, job.id) {
            when (outcome) {
                Outcome.Succeeded -> it.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = it.progress.copy(fraction = 1f, speed = null, eta = null, phase = Phase.DONE),
                    completedAt = NOW(), cancelReason = null,
                )
                is Outcome.Failed -> {
                    val cls = RetryClassifier.classify(outcome.info)
                    val canAuto = cls == RetryClass.TRANSIENT && it.attempt < state.retryPolicy.maxAutoRetries
                    if (canAuto) it.copy(
                        status = DownloadStatus.QUEUED, attempt = it.attempt + 1,
                        error = null, cancelReason = null, progress = it.progress.copy(speed = null, eta = null),
                    ) else it.copy(
                        status = DownloadStatus.FAILED, error = outcome.info.message,
                        completedAt = NOW(), cancelReason = null,
                        progress = it.progress.copy(phase = Phase.FAILED, speed = null, eta = null),
                    )
                }
                is Outcome.Cancelled -> when (outcome.reason) {
                    CancelReason.PAUSE -> it.copy(
                        status = DownloadStatus.PAUSED, cancelReason = null,
                        progress = it.progress.copy(speed = null, eta = "paused", phase = Phase.PAUSED),
                    )
                    CancelReason.USER_CANCEL -> it.copy(
                        status = DownloadStatus.CANCELLED, cancelReason = null, completedAt = NOW(),
                    )
                    CancelReason.SHUTDOWN -> it.copy(
                        status = DownloadStatus.QUEUED, cancelReason = null,
                        progress = it.progress.copy(speed = null, eta = null),
                    )
                }
            }
        }

    private fun reorder(state: QueueState, id: String, beforeId: String?): QueueState {
        val ordered = state.jobs.sortedBy { it.position }.toMutableList()
        val moving = ordered.firstOrNull { it.id == id } ?: return state
        ordered.remove(moving)
        val idx = if (beforeId == null) ordered.size
        else ordered.indexOfFirst { it.id == beforeId }.let { if (it < 0) ordered.size else it }
        ordered.add(idx, moving)
        val repositioned = ordered.mapIndexed { i, j -> j.copy(position = i.toLong()) }
        return state.copy(jobs = state.jobs.map { j -> repositioned.first { it.id == j.id } })
    }

    private inline fun mapJob(state: QueueState, id: String, f: (QueueJob) -> QueueJob) =
        state.copy(jobs = state.jobs.map { if (it.id == id) f(it) else it })
    private inline fun mapJobs(state: QueueState, ids: Set<String>, f: (QueueJob) -> QueueJob) =
        state.copy(jobs = state.jobs.map { if (it.id in ids) f(it) else it })

    private val NON_TERMINAL_INFLIGHT = setOf(DownloadStatus.RUNNING, DownloadStatus.PREPARING, DownloadStatus.PAUSED)

    /** Injectable clock so tests are deterministic; Task 12 sets `QueueReducer.NOW = clock::nowMs`. */
    var NOW: () -> Long = { 0L }
}
