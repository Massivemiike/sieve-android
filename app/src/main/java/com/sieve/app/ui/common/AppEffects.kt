package com.sieve.app.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.sieve.app.di.AppGraph
import com.sieve.queue.service.JobToast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Requests POST_NOTIFICATIONS on first launch (API 33+) and surfaces a snackbar when a queue job
 * reaches a terminal state. The persistent progress notification itself is owned by :queue's
 * foreground QueueService.
 */
@Composable
fun rememberAppSnackbarHost(): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
            AppGraph.queue.state.collect { st ->
                st.jobs.forEach { j ->
                    // Same wording as the system notification and the desktop toasts (kind-aware, names the item).
                    val msg = JobToast.text(j)
                    if (msg != null && seen.add(j.id)) scope.launch { host.showSnackbar(msg) }
                }
            }
        }
    }
    return host
}
