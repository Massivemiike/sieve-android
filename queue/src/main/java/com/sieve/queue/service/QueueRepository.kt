package com.sieve.queue.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * UI-facing facade over [QueueManager]. Fires each command on an app scope and ensures the
 * foreground [QueueService] is running for work that must survive the UI lifecycle. A process-wide
 * singleton so [QueueCommandReceiver] (which has no DI) can reach it via [get].
 */
class QueueRepository private constructor(
    private val appContext: Context,
    private val manager: QueueManager,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<QueueState> = manager.state

    fun enqueue(job: QueueJob) {
        ensureServiceRunning()
        scope.launch { manager.enqueue(job) }
    }
    fun pause(id: String) { scope.launch { manager.pause(id) } }
    fun resume(id: String) { ensureServiceRunning(); scope.launch { manager.resume(id) } }
    fun cancel(id: String) { scope.launch { manager.cancel(id) } }
    fun retry(id: String) { ensureServiceRunning(); scope.launch { manager.retry(id) } }

    fun bindManager(serviceScope: CoroutineScope) = manager.also { it.start(serviceScope) }
    suspend fun rehydrate() = manager.rehydrate()

    private fun ensureServiceRunning() {
        ContextCompat.startForegroundService(appContext, Intent(appContext, QueueService::class.java))
    }

    companion object {
        @Volatile private var INSTANCE: QueueRepository? = null

        fun install(repo: QueueRepository) { INSTANCE = repo }
        fun get(context: Context): QueueRepository = INSTANCE
            ?: error("QueueRepository not installed; :app DI must call install() in Application.onCreate")
        fun create(appContext: Context, manager: QueueManager, scope: CoroutineScope) =
            QueueRepository(appContext, manager, scope).also { install(it) }
    }
}
