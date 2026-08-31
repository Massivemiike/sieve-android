package com.sieve.queue.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.QueueAggregator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Foreground host for the queue. Promotes to foreground within 5 s of start, mirrors state to the
 * notification (throttled to ≤2/s), rehydrates on a START_STICKY null-intent restart, honors the
 * Android 15 dataSync timeout by pausing active work, and stops itself when the queue drains.
 */
class QueueService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repo: QueueRepository

    override fun onCreate() {
        super.onCreate()
        QueueNotification.ensureChannel(this)
        repo = QueueRepository.get(this)
        startForegroundCompat(QueueNotification.build(this, repo.state.value))
        repo.bindManager(serviceScope)
        mirrorStateToNotification()
        watchIdle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) serviceScope.launch { repo.rehydrate() } // START_STICKY recreate → rehydrate
        return START_STICKY
    }

    private fun startForegroundCompat(n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, QueueNotification.FGID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(QueueNotification.FGID, n)
        }
    }

    @OptIn(FlowPreview::class)
    private fun mirrorStateToNotification() {
        serviceScope.launch {
            repo.state.map { QueueNotification.render(it) }.distinctUntilChanged()
                .sample(500)
                .collect {
                    val n = QueueNotification.build(this@QueueService, repo.state.value)
                    getSystemService(NotificationManager::class.java).notify(QueueNotification.FGID, n)
                }
        }
    }

    private fun watchIdle() {
        serviceScope.launch {
            repo.state.map { QueueAggregator.summarize(it.jobs).isIdle }.distinctUntilChanged()
                .collect { idle ->
                    if (idle) {
                        ServiceCompat.stopForeground(this@QueueService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
        }
    }

    /** Android 15 dataSync 6h cap: pause active work and stop; resume on next app open (v1). */
    override fun onTimeout(startId: Int) {
        serviceScope.launch {
            repo.state.value.jobs.filter { it.status == DownloadStatus.RUNNING }.forEach { repo.pause(it.id) }
            ServiceCompat.stopForeground(this@QueueService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    override fun onDestroy() { serviceScope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
