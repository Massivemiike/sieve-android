package com.sieve.app.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.sieve.app.di.AppGraph
import com.sieve.app.ui.queue.CoverRestoredNoticeOnQueue
import com.sieve.app.ui.queue.announceRestoredItems
import com.sieve.queue.service.JobToast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Requests POST_NOTIFICATIONS on first launch (API 33+), surfaces a snackbar when a queue job
 * reaches a terminal state, and tells the user once when the queue came back paused after an upgrade.
 * The persistent progress notification itself is owned by :queue's foreground QueueService.
 */
@Composable
fun rememberAppSnackbars(): AppSnackbars {
    val snackbars = remember { AppSnackbars() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Messages from screens that have no Scaffold of their own (the Android 17 local-network prompt's answer): see SnackbarMessages.
    LaunchedEffect(Unit) {
        SnackbarMessages.flow.collect { m -> scope.launch { snackbars.showMessage(m) } }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        val seen = mutableSetOf<String>()
        runCatching {
            // The queue is restored from the last session: rows already finished then are history, not news.
            AppGraph.queue.rehydrated.first { it }
            AppGraph.queue.state.value.jobs.filter { it.status.isTerminal }.mapTo(seen) { it.id }
            // The one-time "restored paused" migration says so once (the Queue banner stays until the user acts).
            // Own coroutine: the snackbar suspends until it is gone, and the completions below must not wait for it.
            // It stays up until the user acts, but any snackbar below (or opening the Queue tab) takes it down first.
            scope.launch { announceRestoredItems(snackbars, AppGraph.queue::consumeRestoreNotice) }
            AppGraph.queue.state.collect { st ->
                st.jobs.forEach { j ->
                    // Same wording as the system notification and the desktop toasts (kind-aware, names the item).
                    val msg = JobToast.text(j)
                    if (msg != null && seen.add(j.id)) scope.launch { snackbars.show(msg) }
                }
            }
        }
    }
    return snackbars
}

/**
 * The app-wide snackbar slot: shows [snackbars], and keeps the restored-items notice off the Queue tab (whose banner says the
 * same). [currentRoute] is the route being shown.
 */
@Composable
fun AppSnackbarHost(snackbars: AppSnackbars, currentRoute: String?) {
    CoverRestoredNoticeOnQueue(snackbars, currentRoute)
    SnackbarHost(snackbars.state)
}
