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
import com.sieve.queue.core.DownloadStatus
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
            AppGraph.queue.state.collect { st ->
                st.jobs.forEach { j ->
                    val terminal = j.status == DownloadStatus.COMPLETED || j.status == DownloadStatus.FAILED
                    if (terminal && seen.add(j.id)) {
                        val msg = if (j.status == DownloadStatus.COMPLETED) {
                            "Saved: ${j.title.ifBlank { "download" }}"
                        } else {
                            "Failed: ${ErrorHumanizer.humanize(j.error ?: "")}"
                        }
                        scope.launch { host.showSnackbar(msg) }
                    }
                }
            }
        }
    }
    return host
}
