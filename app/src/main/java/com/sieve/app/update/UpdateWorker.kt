package com.sieve.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sieve.app.BuildConfig
import java.util.concurrent.TimeUnit

/** Periodic background update check; posts a notification when a newer version is available. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return when (val status = statusProvider(applicationContext)) {
            is UpdateStatus.Available -> {
                notifier(applicationContext, status.manifest)
                Result.success()
            }
            else -> Result.success()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "sieve-update-check"
        const val CHANNEL_ID = "sieve-updates"
        const val NOTIF_ID = 4242

        /** Overridable seams for tests. */
        @Volatile var statusProvider: suspend (Context) -> UpdateStatus = { _ ->
            AppUpdateChecker(
                UpdateManifestFetcher(UpdateRepository.PRIMARY_MANIFEST_URL, UpdateRepository.MIRROR_MANIFEST_URL, RealHttpGet()),
                BuildConfig.VERSION_CODE,
            ).check()
        }

        @Volatile var notifier: (Context, UpdateManifest) -> Unit = { ctx, m -> postUpdateNotification(ctx, m) }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

private fun postUpdateNotification(context: Context, manifest: UpdateManifest) {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        nm.createNotificationChannel(
            NotificationChannel(UpdateWorker.CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val pending = PendingIntent.getActivity(
        context, 0, launch,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val n = NotificationCompat.Builder(context, UpdateWorker.CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Sieve update available")
        .setContentText("Version ${manifest.versionName} is ready to install")
        .setContentIntent(pending)
        .setAutoCancel(true)
        .build()
    nm.notify(UpdateWorker.NOTIF_ID, n)
}
