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
    private val onCompleted: suspend (QueueJob) -> Unit = {},
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<QueueState> = _state.asStateFlow()

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
        val wasRunning = _state.value.job(id)?.status.let { it == DownloadStatus.RUNNING || it == DownloadStatus.PREPARING }
        dispatch(QueueEvent.Cancel(id))
        if (wasRunning) killJob(id) else drain()
    }
    suspend fun retry(id: String) { dispatch(QueueEvent.Retry(id)); drain() }
    suspend fun setGlobalPaused(paused: Boolean) { dispatch(QueueEvent.SetGlobalPaused(paused)); if (!paused) drain() }

    suspend fun rehydrate() {
        val loaded = persistence.loadAll()
        mutex.withLock { _state.value = _state.value.copy(jobs = loaded) }
        dispatch(QueueEvent.Rehydrate)
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
        val toAdmit = NextItemSelector.select(_state.value, clock.nowMs())
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
                driver.drive(spawnJob) { _state.value.job(id)?.cancelReason }
                    .collect { signal ->
                        if (signal is JobSignal.Terminal && signal.outcome == Outcome.Succeeded) {
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
                        } else {
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
                    } catch (t: Throwable) {
                        android.util.Log.e("SieveFin", "finalize FAILED id=${job.id}", t)
                        throw t
                    }
                    onCompleted(job)
                }
            }
            is Outcome.Cancelled -> if (signal.outcome.reason == CancelReason.USER_CANCEL) output.discard(job, prepared)
            is Outcome.Failed ->
                if (job.status == DownloadStatus.QUEUED) {
                    // reducer chose auto-retry → schedule a delayed re-drain after the backoff
                    scope.launch { delay(_state.value.retryPolicy.backoffMs); drain() }
                } else if (job.status == DownloadStatus.FAILED) {
                    output.discard(job, prepared)
                }
        }
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

    private suspend fun killJob(id: String) {
        val job = _state.value.job(id) ?: return
        when (job.spec) {
            is JobSpec.Download -> downloadPort.cancel(id)
            is JobSpec.Transcode -> transcodePort.cancel(id, graceMs = 3000)
        }
    }
}
