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

class FakeOutputProvider : OutputLocationProvider {
    val prepared = mutableListOf<String>()
    val finalized = mutableListOf<String>()
    val discarded = mutableListOf<String>()
    override suspend fun prepare(job: QueueJob): PreparedOutput {
        prepared += job.id
        return PreparedOutput("/work/${job.id}", "%(title)s.%(ext)s")
    }
    override suspend fun finalize(job: QueueJob, prepared: PreparedOutput): FinalLocation {
        finalized += job.id
        return FinalLocation("/final/${job.id}", null)
    }
    override suspend fun discard(job: QueueJob, prepared: PreparedOutput) { discarded += job.id }
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
