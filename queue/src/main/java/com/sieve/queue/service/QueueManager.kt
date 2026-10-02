package com.sieve.queue.service

import com.sieve.queue.core.ArgReconciler
import com.sieve.queue.core.CancelReason
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.FailureInfo
import com.sieve.queue.core.JobSignal
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.NextItemSelector
import com.sieve.queue.core.Outcome
import com.sieve.queue.core.PreparedOutput
import com.sieve.queue.core.QueueEvent
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueuePersistence
import com.sieve.queue.core.QueueReducer
import com.sieve.queue.core.QueueState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-scoped orchestrator. Owns the [QueueState] + a [Mutex], persists every transition, runs
 * the drain loop via [NextItemSelector], drives admitted jobs through [JobDriver] inside per-kind
 * permits, and resolves output through the [OutputLocationProvider] seam.
 *
 * Pause stamps its reason via the reducer BEFORE killing the process, so the port's Cancelled/Done
 * terminal carries the right reason. The by-id port cancel is authoritative — we do NOT also cancel
 * the collector coroutine, which would race and drop that terminal.
 */
class QueueManager(
    private val driver: JobDriver,
    private val downloadPort: DownloadPort,
    private val transcodePort: TranscodePort,
    private val persistence: QueuePersistence,
    private val output: OutputLocationProvider,
    private val clock: Clock,
    initial: QueueState = QueueState(),
    /** Called after finalize succeeded, with the job in its COMPLETED form (`filePath` = where the file landed). */
    private val onCompleted: suspend (QueueJob) -> Unit = {},
    /** Called once a job lands in FAILED for good (not for a transient auto-retry, a pause or a user cancel). */
    private val onFailed: suspend (QueueJob) -> Unit = {},
    /**
     * Called once a transcode job can no longer need its materialized source copy: it completed, was
     * cancelled by the user, or its row was removed. NOT called when it merely FAILED — Retry re-reads
     * the same input. Best-effort; the host decides what (if anything) is deletable.
     */
    private val releaseSource: suspend (QueueJob) -> Unit = {},
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private val _rehydrated = MutableStateFlow(false)
    /**
     * True once the persisted rows are in [state] (or could not be read). Until then an empty queue only means
     * "not loaded yet" — a restarted service must not read it as idle and stop itself.
     */
    val rehydrated: StateFlow<Boolean> = _rehydrated.asStateFlow()

    private val mutex = Mutex()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private lateinit var scope: CoroutineScope

    init { QueueReducer.NOW = clock::nowMs }

    private suspend fun dispatch(event: QueueEvent) = mutex.withLock { applyLocked(event) }

    /**
     * Reduce + persist. Caller MUST hold [mutex]. Persist runs in NonCancellable so a scope teardown
     * (Service onDestroy) can't interrupt a Room transaction mid-write ("no current transaction").
     */
    private suspend fun applyLocked(event: QueueEvent) {
        val before = _state.value
        val after = QueueReducer.reduce(before, event)
        _state.value = after
        withContext(NonCancellable) {
            val changed = after.jobs.filter { j -> before.job(j.id) != j }
            if (changed.isNotEmpty()) persistence.upsertAll(changed)
            (before.jobs.map { it.id } - after.jobs.map { it.id }.toSet()).forEach { persistence.delete(it) }
        }
    }

    suspend fun enqueue(job: QueueJob) { dispatch(QueueEvent.Enqueue(job)); drain() }
    suspend fun pause(id: String) { dispatch(QueueEvent.Pause(id)); killJob(id) }
    suspend fun resume(id: String) { dispatch(QueueEvent.Resume(id)); drain() }
    suspend fun cancel(id: String) {
        val before = _state.value.job(id)
        val wasRunning = before?.status.let { it == DownloadStatus.RUNNING || it == DownloadStatus.PREPARING }
        dispatch(QueueEvent.Cancel(id))
        if (wasRunning) killJob(id) else drain()
        // Paused and queued rows get no terminal signal on cancel, yet both can own leftovers: a paused or
        // restored partial download, the per-job download archive, a transcode's source copy.
        val cancelledNow = _state.value.job(id)?.status == DownloadStatus.CANCELLED
        if (before?.status == DownloadStatus.PAUSED || (before?.status == DownloadStatus.QUEUED && cancelledNow)) {
            cleanupWorkDir(before)
        }
    }

    /** Removes one finished row (completed / failed / cancelled). Live rows are ignored — cancel them first. */
    suspend fun remove(id: String) {
        val job = mutex.withLock {
            val j = _state.value.job(id)?.takeIf { it.status.isTerminal } ?: return
            applyLocked(QueueEvent.Remove(id))
            j
        }
        cleanupWorkDir(job)
    }

    /** Removes every finished row. Running, queued and paused rows are never touched. */
    suspend fun clearFinished() {
        val removed = mutex.withLock {
            val finished = _state.value.jobs.filter { it.status.isTerminal }
            if (finished.isNotEmpty()) applyLocked(QueueEvent.ClearFinished)
            finished
        }
        removed.forEach { cleanupWorkDir(it) }
    }

    /**
     * Best-effort: a failed cleanup must never stop a row from going away. Never touches the saved output.
     * Used where a job's leftovers are dropped for good (row removed, cancelled from PAUSED), so it also
     * releases the job's source copy.
     */
    private suspend fun cleanupWorkDir(job: QueueJob) {
        withContext(NonCancellable) {
            runCatching { output.cleanup(job) }
                .onFailure { android.util.Log.w("SieveQueue", "work-dir cleanup failed for ${job.id}", it) }
            releaseSourceCopy(job)
        }
    }

    /** Best-effort, and NonCancellable like the other cleanup: the terminal dispatch may be tearing the scope down. */
    private suspend fun releaseSourceCopy(job: QueueJob) {
        if (job.spec !is JobSpec.Transcode) return
        withContext(NonCancellable) {
            runCatching { releaseSource(job) }
                .onFailure { android.util.Log.w("SieveQueue", "source release failed for ${job.id}", it) }
        }
    }
    suspend fun retry(id: String) { dispatch(QueueEvent.Retry(id)); drain() }
    suspend fun setGlobalPaused(paused: Boolean) { dispatch(QueueEvent.SetGlobalPaused(paused)); if (!paused) drain() }

    /**
     * Applies new concurrency caps (clamped by the reducer) and re-drains so a raised cap admits
     * waiting jobs at once. Lowering never kills a running job — it just stops new admissions until
     * the running count drops under the cap.
     */
    suspend fun setLimits(downloads: Int, transcodes: Int) {
        dispatch(QueueEvent.SetMaxDownloads(downloads))
        dispatch(QueueEvent.SetMaxTranscodes(transcodes))
        drain()
    }

    /** Keeps the caps in sync with a live (downloads, transcodes) source such as the persisted settings. */
    suspend fun followLimits(limits: Flow<Pair<Int, Int>>) {
        limits.distinctUntilChanged().collect { (downloads, transcodes) -> setLimits(downloads, transcodes) }
    }

    /**
     * Loads the persisted queue, ONCE per process (later calls are no-ops): finished rows come back as they
     * were, work the dead process left in flight (running / preparing / paused) comes back QUEUED, as on
     * desktop. It merges into the live state rather than replacing it, so a job enqueued — or already
     * running — before the load finished is neither dropped nor reverted (it queues behind the restored rows).
     * A store that cannot be read
     * leaves the queue empty but still ends the load, so nothing waits on [rehydrated] forever.
     */
    suspend fun rehydrate() {
        if (_rehydrated.value) return
        val loaded = try {
            persistence.loadAll()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.e("SieveQueue", "loading the persisted queue failed", t)
            emptyList()
        }
        mutex.withLock {
            if (_rehydrated.value) return // another caller finished the load while this one was reading
            val before = _state.value
            val fresh = loaded.filter { before.job(it.id) == null }
            val restored = QueueReducer.reduce(QueueState(jobs = fresh), QueueEvent.Rehydrate).jobs
            // A job enqueued before the load took its position from the still-empty queue, so it ties with (or
            // jumps ahead of) the older restored rows: queue it behind them, keeping its own order.
            val floor = restored.maxOfOrNull { it.position }
            val live = if (floor == null || before.jobs.all { it.position > floor }) before.jobs
            else before.jobs.sortedBy { it.position }.mapIndexed { i, j -> j.copy(position = floor + 1 + i) }
            _state.value = before.copy(jobs = live + restored)
            withContext(NonCancellable) {
                val changed = restored.filter { r -> fresh.first { it.id == r.id } != r } + live.filter { before.job(it.id) != it }
                // Best-effort: the rows are already QUEUED in memory and persist with their next change.
                if (changed.isNotEmpty()) runCatching { persistence.upsertAll(changed) }
                    .onFailure { android.util.Log.w("SieveQueue", "persisting the restored rows failed", it) }
            }
            _rehydrated.value = true
        }
    }

    fun start(scope: CoroutineScope) {
        this.scope = scope
        // React to any state edge with a drain attempt (analog of desktop's repeated startNextInQueue).
        scope.launch {
            state.map { it.jobs.map { j -> j.id to j.status } }.distinctUntilChanged().collect { drain() }
        }
        // Initial kick: covers rehydrate (QUEUED jobs loaded before start with no enqueue to trigger a drain).
        scope.launch { drain() }
    }

    // Admission is atomic under the mutex: select → MarkPreparing → launchJob run without another
    // drain interleaving. Multiple drain() calls race in production (two start() drains + finally +
    // retry, all on Dispatchers.Default), so the whole claim must be serialized, not just the reduce.
    private suspend fun drain() = mutex.withLock {
        // Admission needs a LIVE host scope: launchJob launches on `scope` (the service's, via
        // bindManager). Between the service idling out (scope cancelled) and the next bind, a drain
        // would MarkPreparing and then silently drop the launch on the dead scope — wedging the job
        // in PREPARING forever (the rebound service's drain only admits QUEUED). Leave jobs QUEUED;
        // start()'s kick re-drains once a live scope is bound.
        if (!this::scope.isInitialized || !scope.isActive) return@withLock
        // A row put back to QUEUED (Retry, Resume) while its previous run is still unwinding is not claimed yet:
        // that run's coroutine stays in runningJobs until its finally block, so launchJob would refuse the launch
        // and leave the row PREPARING forever. It waits as QUEUED; the unwinding run's own finally re-drains.
        val toAdmit = NextItemSelector.select(_state.value, clock.nowMs()).filterNot { runningJobs.containsKey(it) }
        if (toAdmit.isEmpty()) return@withLock
        applyLocked(QueueEvent.MarkPreparing(toAdmit))
        for (id in toAdmit) launchJob(id)
    }

    private fun launchJob(id: String) {
        if (runningJobs.containsKey(id)) return
        val job = _state.value.job(id) ?: return
        val coroutine = scope.launch {
            var prepared: PreparedOutput? = null
            try {
                prepared = output.prepare(job)
                // A pause/cancel that arrived during prepare() (a no-op for the not-yet-started port)
                // must be honored before spawning — otherwise the job runs to completion regardless.
                val pending = _state.value.job(id)?.cancelReason
                if (pending != null) {
                    val terminal = JobSignal.Terminal(id, Outcome.Cancelled(pending))
                    dispatch(QueueEvent.Signal(terminal))
                    onSignal(id, terminal, prepared)
                    return@launch
                }
                val spawnJob = withOutput(job, prepared)
                dispatch(QueueEvent.MarkRunning(id))
                var firstSignalSeen = false
                driver.drive(spawnJob) { _state.value.job(id)?.cancelReason }
                    .collect { signal ->
                        // The port can't act on a cancel that lands before its process exists (the engine also
                        // forgets it at the start of a run). The first signal means the process is up: if a
                        // pause/cancel was stamped by now, send it again. Own coroutine: a transcode cancel
                        // waits out its grace period and must not stall the collection.
                        if (!firstSignalSeen && signal !is JobSignal.Terminal) {
                            firstSignalSeen = true
                            if (_state.value.job(id)?.cancelReason != null) scope.launch { killJob(id) }
                        }
                        // A cancel that still got in after the point of no return (the run was already past its
                        // last check) wins over the run finishing: don't put a file the user cancelled in their
                        // folder. A pause is different — the file is complete, so it just completes.
                        val cancelledMeanwhile = signal is JobSignal.Terminal && signal.outcome == Outcome.Succeeded &&
                            _state.value.job(id)?.cancelReason == CancelReason.USER_CANCEL
                        if (cancelledMeanwhile) {
                            val cancelled = JobSignal.Terminal(id, Outcome.Cancelled(CancelReason.USER_CANCEL))
                            dispatch(QueueEvent.Signal(cancelled))
                            onSignal(id, cancelled, prepared)
                        } else if (signal is JobSignal.Terminal && signal.outcome == Outcome.Succeeded) {
                            // Finalize BEFORE dispatching COMPLETED. The COMPLETED dispatch flips the
                            // queue idle, which stops QueueService → onDestroy cancels the manager's
                            // scope → a finalize still copying out of the work dir dies with
                            // JobCancellationException and the file never reaches user storage.
                            // A real finalize failure surfaces as FAILED instead of a fake success.
                            val fin = runCatching { onSignal(id, signal, prepared) }
                            val terminal = fin.exceptionOrNull()?.let { t ->
                                if (t is CancellationException) throw t
                                JobSignal.Terminal(id, Outcome.Failed(FailureInfo("saving output failed: ${t.message}")))
                            } ?: signal
                            dispatch(QueueEvent.Signal(terminal))
                            // A failed save never reaches onSignal's failure branch, so announce it here (unless the
                            // reducer chose an automatic retry, which is not a failure yet).
                            if (terminal.outcome is Outcome.Failed) {
                                _state.value.job(id)?.takeIf { it.status == DownloadStatus.FAILED }?.let { notifyFailed(it) }
                            }
                        } else {
                            // A failed run's work dir is settled BEFORE FAILED is dispatched, like finalize before
                            // COMPLETED: the finished files are copied out while the row is still live, so the
                            // queue is not idle (the service stays up), and Retry / Remove cannot race the copy.
                            if (signal is JobSignal.Terminal && signal.outcome is Outcome.Failed && endsFailed(id, signal)) {
                                keepFinishedFiles(_state.value.job(id) ?: job, prepared)
                            }
                            dispatch(QueueEvent.Signal(signal))
                            onSignal(id, signal, prepared)
                        }
                    }
            } catch (c: CancellationException) {
                throw c
            } finally {
                runningJobs.remove(id)
                drain()
            }
        }
        runningJobs[id] = coroutine
    }

    private suspend fun onSignal(id: String, signal: JobSignal, prepared: PreparedOutput) {
        if (signal !is JobSignal.Terminal) return
        val job = _state.value.job(id) ?: return
        when (signal.outcome) {
            Outcome.Succeeded -> {
                // NonCancellable: the copy out of the work dir must survive service teardown
                // (idle stop, dataSync timeout) once the download itself has succeeded.
                withContext(NonCancellable) {
                    try {
                        val loc = output.finalize(job, prepared)
                        android.util.Log.i("SieveFin", "finalize OK id=${job.id} -> ${loc.displayPath} uri=${loc.uri}")
                        // Remember where the file landed (content Uri when known) so the row can open it.
                        dispatch(QueueEvent.OutputSaved(job.id, loc.uri ?: loc.displayPath))
                    } catch (t: Throwable) {
                        android.util.Log.e("SieveFin", "finalize FAILED id=${job.id}", t)
                        throw t
                    }
                    // The COMPLETED dispatch comes right after this (see launchJob), so hand over the job in
                    // its finished form: saved location recorded, status as it is about to be persisted.
                    onCompleted((_state.value.job(job.id) ?: job).copy(status = DownloadStatus.COMPLETED))
                    releaseSourceCopy(job)
                }
            }
            // A user cancel is final: drop the per-job archive with the work dir (discard keeps it for a Retry).
            is Outcome.Cancelled -> if (signal.outcome.reason == CancelReason.USER_CANCEL) cleanupWorkDir(job)
            is Outcome.Failed ->
                if (job.status == DownloadStatus.QUEUED) {
                    // reducer chose auto-retry → schedule a delayed re-drain after the backoff
                    scope.launch { delay(_state.value.retryPolicy.backoffMs); drain() }
                } else if (job.status == DownloadStatus.FAILED) {
                    // The work dir was already settled (see launchJob); the row carries the saved location, if any.
                    notifyFailed(job)
                }
        }
    }

    /** Would this Failed terminal leave the row FAILED, rather than hand it to an automatic retry? */
    private fun endsFailed(id: String, terminal: JobSignal.Terminal): Boolean =
        QueueReducer.reduce(_state.value, QueueEvent.Signal(terminal)).job(id)?.status == DownloadStatus.FAILED

    /**
     * Settles a failing job's work dir BEFORE its row shows FAILED: saves whatever finished in it, else clears
     * it. yt-dlp exits non-zero when ONE playlist entry fails, having downloaded the rest; discarding the dir
     * would delete all of that, and no Retry could bring it back. The row then goes FAILED (with the error and
     * the saved location), and the per-job download archive makes its Retry skip the saved entries instead of
     * saving duplicates. Known limit: yt-dlp archives an entry only after post-processing, so one whose media
     * finished but whose post-processor errored is saved here yet re-downloaded (and saved again) by a Retry.
     * Downloads only: a failed ffmpeg run leaves a truncated file, which is no result. A failed save keeps the
     * work dir (the finished files are still in it) for the Retry instead of deleting them. Never throws: the
     * FAILED dispatch that follows must happen whatever became of the files. NonCancellable: the copy must
     * survive a service teardown once started, like finalize.
     */
    private suspend fun keepFinishedFiles(job: QueueJob, prepared: PreparedOutput) {
        withContext(NonCancellable) {
            try {
                val saved = if (job.spec is JobSpec.Download) output.salvage(job, prepared) else null
                if (saved == null) {
                    output.discard(job, prepared)
                } else {
                    android.util.Log.i("SieveFin", "salvage OK id=${job.id} -> ${saved.displayPath} uri=${saved.uri}")
                    dispatch(QueueEvent.OutputSaved(job.id, saved.uri ?: saved.displayPath))
                }
            } catch (t: Throwable) {
                android.util.Log.e("SieveFin", "settling the work dir FAILED id=${job.id}; leaving it for the Retry", t)
            }
        }
    }

    /** The callback is best-effort (it posts a notification): its failure must never touch the queue. */
    private suspend fun notifyFailed(job: QueueJob) {
        withContext(NonCancellable) { runCatching { onFailed(job) } }
    }

    /** Rewrite the engine args of QUEUED download rows when a global setting changes (rewriteQueued* analog). */
    suspend fun reconcileQueuedArgs(transform: (List<String>) -> List<String>) = mutex.withLock {
        val updated = _state.value.jobs.map { j ->
            val spec = j.spec
            if (j.status == DownloadStatus.QUEUED && spec is JobSpec.Download)
                j.copy(spec = JobSpec.Download(spec.url, transform(spec.engineArgs)))
            else j
        }
        _state.value = _state.value.copy(jobs = updated)
        persistence.upsertAll(updated.filter { it.status == DownloadStatus.QUEUED })
    }

    /**
     * Downloads: inject -P/-o via ArgReconciler. Transcode: the physical outputPath threading from
     * prepare() to FfmpegRunner is a follow-up (JobSpec.Transcode has no outputPath field, and only
     * downloads are exercised end-to-end in this plan's smoke); the transcode module is smoke-tested
     * standalone in plan #2.
     */
    private fun withOutput(job: QueueJob, prepared: PreparedOutput): QueueJob = when (val s = job.spec) {
        is JobSpec.Download -> job.copy(
            spec = JobSpec.Download(s.url, ArgReconciler.injectDownloadOutput(ArgReconciler.ensureContinue(s.engineArgs), prepared)),
        )
        is JobSpec.Transcode -> job.copy(
            spec = s.copy(outputPath = "${prepared.workDir}/${prepared.workFileTemplate}"),
        )
    }

    /**
     * The Pause/Cancel is already stamped on the row, so the job ends correctly whether or not the kill
     * lands: a failure here is logged, never propagated — this runs in fire-and-forget app-scope
     * launches, where an exception would take the whole process down.
     */
    private suspend fun killJob(id: String) {
        val job = _state.value.job(id) ?: return
        try {
            when (job.spec) {
                is JobSpec.Download -> downloadPort.cancel(id)
                is JobSpec.Transcode -> transcodePort.cancel(id, graceMs = 3000)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.w("SieveQueue", "kill failed for $id", t)
        }
    }
}
