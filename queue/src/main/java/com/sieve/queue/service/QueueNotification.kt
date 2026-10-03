package com.sieve.queue.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.FailureText
import com.sieve.queue.core.JobKind
import com.sieve.queue.core.QueueAggregator
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState

enum class NotifAction { PAUSE, RESUME, CANCEL }

/** Pure model of the "finished" notification (see [QueueNotification.renderDone]). */
data class DoneModel(
    val title: String,
    val text: String?,
    val failed: Boolean,
    /** The content Uri a tap opens; null = the tap just opens the app (or the Queue for a failure). */
    val openUri: String?,
)

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
    /** Set on the launch intent of a failure notification: the app should land on the Queue. */
    const val EXTRA_OPEN_QUEUE = "com.sieve.queue.OPEN_QUEUE"
    private const val DONE_ID_SALT = "queue_done:"
    private val IN_PROGRESS = setOf(DownloadStatus.RUNNING, DownloadStatus.PREPARING, DownloadStatus.QUEUED)

    fun requestCode(id: String, action: NotifAction) = id.hashCode() * 31 + action.ordinal

    /**
     * The verb of the running title ("<verb> 1 of 3"). It names the work only when every counted job is that kind,
     * so the verb is true of the whole "of M": all downloads read "Downloading" (as ever), all transcodes
     * "Transcoding" (the Queue chip's word), and a mix of the two the neutral "Working on". [live] always holds the
     * running job, so it is never empty.
     */
    private fun runningVerb(live: List<QueueJob>): String = when {
        live.all { it.kind == JobKind.DOWNLOAD } -> "Downloading"
        live.all { it.kind == JobKind.TRANSCODE } -> "Transcoding"
        else -> "Working on"
    }

    /** Pure mapping from queue state to the notification content. */
    fun render(state: QueueState): NotifModel {
        val sum = QueueAggregator.summarize(state.jobs)
        val active = state.jobs.firstOrNull { it.status == DownloadStatus.RUNNING }
        val paused = state.jobs.firstOrNull { it.status == DownloadStatus.PAUSED }
        val preparing = state.jobs.any { it.status == DownloadStatus.PREPARING }
        return when {
            active != null -> {
                val frac = active.progress.fraction
                val pct = ((frac ?: 0f) * 100).toInt()
                // Count only work that will run: finished rows stay in the restored queue until cleared, and paused
                // ones (the user's, or held after an upgrade) wait for a resume.
                val live = state.jobs.filter { it.status in IN_PROGRESS }
                val runningIdx = 1 + live.indexOfFirst { it.id == active.id }.coerceAtLeast(0)
                NotifModel(
                    title = "${runningVerb(live)} $runningIdx of ${live.size}",
                    text = "${active.title.ifBlank { "Item" }} · $pct%",
                    progress = pct, indeterminate = frac == null,
                    actions = listOf(NotifAction.PAUSE, NotifAction.CANCEL), actionTargetId = active.id,
                )
            }
            paused != null && !preparing -> NotifModel(
                title = "Paused — ${sum.queued + 1} remaining",
                text = paused.title.ifBlank { "Item" },
                progress = ((paused.progress.fraction ?: 0f) * 100).toInt(), indeterminate = false,
                actions = listOf(NotifAction.RESUME, NotifAction.CANCEL), actionTargetId = paused.id,
            )
            else -> NotifModel("Preparing…", "", 0, indeterminate = true, actions = emptyList(), actionTargetId = null)
        }
    }

    /**
     * What to tell the user when a job ends: "Downloaded: <title>" / "Transcoded: <title>" (tap opens
     * the file when its Uri is known), or "Failed: <title>" with the humanized error. Null for any
     * status that isn't a finished outcome (cancelled, paused and queued rows stay quiet).
     */
    fun renderDone(job: QueueJob): DoneModel? {
        val name = job.title.ifBlank { "file" }
        return when (job.status) {
            DownloadStatus.COMPLETED -> {
                val verb = if (job.kind == JobKind.TRANSCODE) "Transcoded" else "Downloaded"
                val uri = job.filePath?.takeIf { it.trim().startsWith("content://") }
                DoneModel("$verb: $name", if (uri != null) "Tap to open" else null, failed = false, openUri = uri)
            }
            DownloadStatus.FAILED ->
                DoneModel("Failed: $name", FailureText.text(job), failed = true, openUri = null)
            else -> null
        }
    }

    /** Unique per job (a retry of the same job replaces its own notification) and never the foreground id. */
    fun doneId(jobId: String): Int = (DONE_ID_SALT + jobId).hashCode().let { if (it == FGID) it + 1 else it }

    /** Android 13+ needs the runtime grant; the user can also switch notifications off for the app/channel. */
    fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    /**
     * Posts the "finished" notification for [job] on the Completed channel. Returns whether it was
     * posted; a missing permission, disabled notifications or any platform failure just returns false
     * (this runs inside the queue's completion path and must never break it).
     */
    fun postDone(ctx: Context, job: QueueJob): Boolean = try {
        val m = renderDone(job)
        if (m == null || !canPost(ctx)) {
            false
        } else {
            ensureChannel(ctx)
            val b = NotificationCompat.Builder(ctx, DONE_CHANNEL_ID)
                .setSmallIcon(if (m.failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
                .setContentTitle(m.title)
                .setAutoCancel(true)
                .setCategory(if (m.failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            m.text?.let { b.setContentText(it) }
            doneTapIntent(ctx, job.id, m)?.let { b.setContentIntent(it) }
            // The Uri grant rides on the PendingIntent; the poster (this app) owns the grant.
            NotificationManagerCompat.from(ctx).notify(doneId(job.id), b.build())
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Tap target: the finished file when its Uri is known, the Queue for a failure, else just the app. */
    private fun doneTapIntent(ctx: Context, jobId: String, m: DoneModel): PendingIntent? {
        val uri = OutputIntents.openableUri(m.openUri)
        val intent = when {
            uri != null -> OutputIntents.viewIntent(ctx, uri)
            else -> appIntent(ctx, openQueue = m.failed)
        } ?: return null
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(ctx, doneId(jobId), intent, flags)
    }

    /**
     * An explicit intent for the app's launcher activity (found via the package manager — :queue
     * doesn't know :app's classes). Not a LAUNCHER intent on purpose: the system would only bring an
     * existing task forward and drop the extra, while this reaches MainActivity.onNewIntent.
     */
    fun appIntent(ctx: Context, openQueue: Boolean): Intent? {
        val component = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.component ?: return null
        return Intent().setComponent(component).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (openQueue) putExtra(EXTRA_OPEN_QUEUE, true)
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
