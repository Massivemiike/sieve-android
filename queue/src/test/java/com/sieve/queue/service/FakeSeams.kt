package com.sieve.queue.service

import com.sieve.engine.model.DownloadProgress
import com.sieve.engine.repo.EngineEvent
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.FinalLocation
import com.sieve.queue.core.PreparedOutput
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueuePersistence
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow

class FakeClock(var t: Long = 1000L) : Clock {
    override fun nowMs() = t
}

class FakeOutputProvider(
    private val prepareGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    /** When set, finalize reports `<prefix><jobId>` as the saved file's Uri (null = the sink has no Uri). */
    private val finalUriPrefix: String? = null,
    /** When true, finalize throws (disk full, SAF revoked, ...). */
    private val failFinalize: Boolean = false,
    /** What salvage reports for a failed run: where the finished files landed, or null = nothing finished. */
    private val salvagedTo: FinalLocation? = null,
    /** When true, salvage throws (the copy out of the work dir failed). */
    private val failSalvage: Boolean = false,
    /** When set, salvage waits here before reporting — a multi-GB copy still under way. */
    private val salvageGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
) : OutputLocationProvider {
    val prepared = mutableListOf<String>()
    val finalized = mutableListOf<String>()
    val discarded = mutableListOf<String>()
    val salvaged = mutableListOf<String>()
    override suspend fun prepare(job: QueueJob): PreparedOutput {
        prepareGate?.await() // when set, holds the job in PREPARING until released
        prepared += job.id
        return PreparedOutput("/work/${job.id}", "%(title)s.%(ext)s")
    }
    override suspend fun finalize(job: QueueJob, prepared: PreparedOutput): FinalLocation {
        if (failFinalize) throw java.io.IOException("disk full")
        finalized += job.id
        return FinalLocation("/final/${job.id}", finalUriPrefix?.let { it + job.id })
    }
    override suspend fun discard(job: QueueJob, prepared: PreparedOutput) { discarded += job.id }
    override suspend fun salvage(job: QueueJob, prepared: PreparedOutput): FinalLocation? {
        salvaged += job.id
        salvageGate?.await()
        if (failSalvage) throw java.io.IOException("copy failed")
        return salvagedTo
    }
}

class InMemoryPersistence : QueuePersistence {
    val store = MutableStateFlow<Map<String, QueueJob>>(emptyMap())
    override suspend fun loadAll() = store.value.values.sortedBy { it.position }
    override suspend fun upsert(job: QueueJob) { store.value = store.value + (job.id to job) }
    override suspend fun upsertAll(jobs: List<QueueJob>) { store.value = store.value + jobs.associateBy { it.id } }
    override suspend fun updateStatus(id: String, status: DownloadStatus) { store.value[id]?.let { upsert(it.copy(status = status)) } }
    override suspend fun delete(id: String) { store.value = store.value - id }
    override suspend fun prune(cutoff: Long): Int = 0
}

/**
 * A DownloadPort whose flow emits one progress then stays open until [cancel] injects a `Cancelled`
 * terminal — lets the pause test assert the ordering (reason stamped → cancel(id) → Cancelled → PAUSED).
 * The channel is registered before the progress is sent, so cancel always finds it.
 */
class CancellableDownloadPort : DownloadPort {
    private val channels = mutableMapOf<String, Channel<EngineEvent>>()
    val cancelled = mutableListOf<String>()

    override fun download(id: String, url: String, args: List<String>): Flow<EngineEvent> = channelFlow {
        val ch = Channel<EngineEvent>(Channel.UNLIMITED)
        channels[id] = ch
        send(EngineEvent.Progress(DownloadProgress(0.3f, "1MiB/s", "00:10", "—")))
        for (ev in ch) send(ev)
    }

    override fun cancel(id: String): Boolean {
        cancelled += id
        val ch = channels[id] ?: return false
        ch.trySend(EngineEvent.Cancelled)
        ch.close()
        return true
    }
}
