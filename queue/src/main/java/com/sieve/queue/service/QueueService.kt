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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Foreground host for the queue. Promotes to foreground within 5 s of start, mirrors state to the
 * notification (throttled to ≤2/s), honors the Android 15 dataSync timeout by pausing active work, and
 * stops itself when the queue drains. The persisted queue is restored by [QueueRepository.create] when the
 * process starts (a START_STICKY restart included); the idle check waits for that load to finish.
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
            // Until the persisted rows are loaded the in-memory queue is empty, i.e. "idle": judging it then
            // would stop a restarted service before the downloads it was restarted for are even restored.
            combine(repo.rehydrated, repo.state.map { QueueAggregator.summarize(it.jobs).isIdle }) { loaded, idle -> loaded && idle }
                .distinctUntilChanged()
                .collect { idle ->
                    if (idle) {
                        ServiceCompat.stopForeground(this@QueueService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
        }
    }

    /**
     * Android 15+ dataSync 6 h cap: pause active work and stop; resume on next app open (v1). From API 35 the platform
     * reports it HERE, with the service's type; the one-argument overload below is only the API 34 shortService
     * callback, so without this override the handler never ran and the platform killed the app a few seconds later
     * (ForegroundServiceDidNotStopInTimeException).
     */
    override fun onTimeout(startId: Int, fgsType: Int) { stopForTimeLimit() }

    /** The API 34 shortService callback. This service never runs as one; if it is ever called it means the same. */
    override fun onTimeout(startId: Int) { stopForTimeLimit() }

    private fun stopForTimeLimit() {
        serviceScope.launch {
            try {
                repo.state.value.jobs.filter { it.status == DownloadStatus.RUNNING }.forEach { repo.pause(it.id) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // The stop below must happen whatever became of the pauses: the platform allows only a few seconds.
                android.util.Log.e("SieveQueue", "pausing the running work at the time limit failed", t)
            }
            ServiceCompat.stopForeground(this@QueueService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            // Unconditional on purpose: a start request that arrived after the platform's startId would make
            // stopSelf(startId) a no-op and leave the service running into the platform's exception.
            stopSelf()
        }
    }

    override fun onDestroy() { serviceScope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
