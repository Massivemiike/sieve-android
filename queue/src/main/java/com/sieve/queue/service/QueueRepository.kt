package com.sieve.queue.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import com.sieve.queue.core.RestoreHold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * UI-facing facade over [QueueManager]. Fires each command on an app scope and ensures the
 * foreground [QueueService] is running for work that must survive the UI lifecycle. A process-wide
 * singleton so [QueueCommandReceiver] (which has no DI) can reach it via [get]. Creating it restores the
 * persisted queue, so every launch — cold start, notification tap, service restart — sees its history.
 */
class QueueRepository private constructor(
    private val appContext: Context,
    private val manager: QueueManager,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<QueueState> = manager.state

    /** True once the persisted queue is in [state]; before that an empty queue just means "not loaded yet". */
    val rehydrated: StateFlow<Boolean> = manager.rehydrated

    /** The rows the one-time restore brought back paused and that still wait for the user, and whether the banner was dismissed. */
    val restore: StateFlow<RestoreHold> = manager.restoreHold

    fun enqueue(job: QueueJob) {
        ensureServiceRunning()
        scope.launch { manager.enqueue(job) }
    }
    fun pause(id: String) { scope.launch { manager.pause(id) } }
    fun resume(id: String) { ensureServiceRunning(); scope.launch { manager.resume(id) } }
    fun cancel(id: String) { scope.launch { manager.cancel(id) } }
    fun retry(id: String) { ensureServiceRunning(); scope.launch { manager.retry(id) } }
    fun remove(id: String) { scope.launch { manager.remove(id) } }
    fun clearFinished() { scope.launch { manager.clearFinished() } }

    /** "Resume all" on the restored rows. */
    fun resumeHeld() {
        if (restore.value.heldIds.isEmpty()) return
        ensureServiceRunning()
        scope.launch { manager.resumeHeld() }
    }
    fun dismissRestoreBanner() { scope.launch { manager.dismissRestoreBanner() } }

    /** How many rows this launch's one-time restore brought back paused, once (then 0). */
    fun consumeRestoreNotice(): Int = manager.consumeRestoreNotice()

    /** Follows a live (downloads, transcodes) concurrency source for the life of the app scope. */
    fun followLimits(limits: Flow<Pair<Int, Int>>) { scope.launch { manager.followLimits(limits) } }

    fun bindManager(serviceScope: CoroutineScope) = manager.also { it.start(serviceScope) }

    /**
     * Loads the persisted queue (once), then wakes the service when it brought back work to do — the
     * downloads a killed process left unfinished. That start can be refused when the process was spawned
     * in the background (Android 12+); the work then simply waits for the next enqueue or app open.
     */
    suspend fun rehydrate() {
        manager.rehydrate()
        if (state.value.jobs.any { it.status == DownloadStatus.QUEUED }) {
            runCatching { ensureServiceRunning() }
                .onFailure { android.util.Log.w("SieveQueue", "restored queue could not start the service", it) }
        }
    }

    private fun ensureServiceRunning() {
        ContextCompat.startForegroundService(appContext, Intent(appContext, QueueService::class.java))
    }

    companion object {
        @Volatile private var INSTANCE: QueueRepository? = null

        fun install(repo: QueueRepository) { INSTANCE = repo }
        fun get(context: Context): QueueRepository = INSTANCE
            ?: error("QueueRepository not installed; :app DI must call install() in Application.onCreate")
        fun create(appContext: Context, manager: QueueManager, scope: CoroutineScope) =
            QueueRepository(appContext, manager, scope).also {
                install(it)
                scope.launch { it.rehydrate() }
            }
    }
}
