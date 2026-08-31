package com.sieve.queue.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.QueueAggregator
import com.sieve.queue.core.QueueState

enum class NotifAction { PAUSE, RESUME, CANCEL }

/** Pure render model — unit-tested without a real Notification/Context. */
data class NotifModel(
    val title: String,
    val text: String,
    val progress: Int,
    val indeterminate: Boolean,
    val actions: List<NotifAction>,
    val actionTargetId: String?,
)

object QueueNotification {
    const val CHANNEL_ID = "queue"
    const val DONE_CHANNEL_ID = "queue_done"
    const val FGID = 1001
    const val EXTRA_ACTION = "com.sieve.queue.ACTION"
    const val EXTRA_ITEM = "com.sieve.queue.ITEM"

    fun requestCode(id: String, action: NotifAction) = id.hashCode() * 31 + action.ordinal

    /** Pure mapping from queue state to the notification content. */
    fun render(state: QueueState): NotifModel {
        val sum = QueueAggregator.summarize(state.jobs)
        val active = state.jobs.firstOrNull { it.status == DownloadStatus.RUNNING }
        val paused = state.jobs.firstOrNull { it.status == DownloadStatus.PAUSED }
        return when {
            active != null -> {
                val frac = active.progress.fraction
                val pct = ((frac ?: 0f) * 100).toInt()
                val runningIdx = 1 + state.jobs.indexOfFirst { it.id == active.id }.coerceAtLeast(0)
                NotifModel(
                    title = "Downloading $runningIdx of ${sum.total}",
                    text = "${active.title.ifBlank { "Item" }} · $pct%",
                    progress = pct, indeterminate = frac == null,
                    actions = listOf(NotifAction.PAUSE, NotifAction.CANCEL), actionTargetId = active.id,
                )
            }
            paused != null -> NotifModel(
                title = "Paused — ${sum.queued + 1} remaining",
                text = paused.title.ifBlank { "Item" },
                progress = ((paused.progress.fraction ?: 0f) * 100).toInt(), indeterminate = false,
                actions = listOf(NotifAction.RESUME, NotifAction.CANCEL), actionTargetId = paused.id,
            )
            else -> NotifModel("Preparing…", "", 0, indeterminate = true, actions = emptyList(), actionTargetId = null)
        }
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL_ID, "Completed", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun build(ctx: Context, state: QueueState): Notification {
        val m = render(state)
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(m.title).setContentText(m.text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, m.progress, m.indeterminate)
        m.actionTargetId?.let { id -> for (a in m.actions) b.addAction(action(ctx, a, id)) }
        return b.build()
    }

    private fun action(ctx: Context, a: NotifAction, itemId: String): NotificationCompat.Action {
        val intent = Intent(ctx, QueueCommandReceiver::class.java).apply {
            putExtra(EXTRA_ACTION, a.name); putExtra(EXTRA_ITEM, itemId)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(ctx, requestCode(itemId, a), intent, flags)
        return NotificationCompat.Action(0, a.name, pi)
    }
}
