package com.sieve.app.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.sieve.app.ui.common.ChipKind
import com.sieve.app.ui.common.EmptyState
import com.sieve.app.ui.common.ErrorHumanizer
import com.sieve.app.ui.common.SieveChip
import com.sieve.app.ui.common.SieveProgress
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.Phase
import com.sieve.queue.core.QueueJob
import com.sieve.queue.service.OutputIntents
import kotlinx.coroutines.launch

@Composable
fun QueueRoute(
    vm: QueueViewModel = viewModel(factory = viewModelFactory { initializer { QueueViewModel.from() } }),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    QueueScreen(
        state, vm::pause, vm::resume, vm::retry, vm::cancel,
        onRemove = vm::remove,
        onClearFinished = vm::clearFinished,
        onOpen = { job ->
            if (!OutputIntents.open(ctx, job.filePath)) scope.launch { snackbar.showSnackbar("Can't open this file") }
        },
        snackbarHost = snackbar,
    )
}

@Composable
fun QueueScreen(
    state: QueueUiState,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit = {},
    onClearFinished: () -> Unit = {},
    onOpen: (QueueJob) -> Unit = {},
    snackbarHost: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = { QueueTopBar(state, onClearFinished) },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        if (state.jobs.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxWidth()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "Queue is empty",
                    subtitle = "Tap Download to add a link.",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxWidth(),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.jobs, key = { it.id }) { job ->
                    JobRow(job, onPause, onResume, onRetry, onCancel, onRemove, onOpen)
                }
            }
        }
    }
}

@Composable
private fun QueueTopBar(state: QueueUiState, onClearFinished: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Queue", style = MaterialTheme.typography.titleLarge)
            Text(
                "${state.summary.running} active · ${state.summary.queued} queued",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { confirming = true }, enabled = state.finished > 0, modifier = Modifier.testTag("clear_finished")) {
            Text("Clear finished")
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Clear finished?") },
            text = { Text("Removes ${state.finished} finished ${if (state.finished == 1) "item" else "items"} from the queue. Saved files stay where they are.") },
            confirmButton = {
                TextButton(onClick = { confirming = false; onClearFinished() }, modifier = Modifier.testTag("clear_finished_confirm")) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun JobRow(
    job: QueueJob,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onOpen: (QueueJob) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val running = job.status == DownloadStatus.RUNNING || job.status == DownloadStatus.PREPARING
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(cs.surface)
            .border(1.dp, cs.outline, RoundedCornerShape(13.dp)).testTag("job_${job.id}").padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            AsyncImage(
                model = job.thumbnailUrl.ifBlank { null },
                contentDescription = null,
                modifier = Modifier.width(74.dp).height(44.dp).clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHigh),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    job.title.ifBlank { job.spec.let { "Download" } },
                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                StateChip(job)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (job.status) {
                        DownloadStatus.RUNNING, DownloadStatus.PREPARING -> {
                            IconBtn(Icons.Filled.Pause, "pause_${job.id}") { onPause(job.id) }
                            IconBtn(Icons.Filled.Close, "cancel_${job.id}") { onCancel(job.id) }
                        }
                        DownloadStatus.PAUSED -> {
                            IconBtn(Icons.Filled.PlayArrow, "resume_${job.id}") { onResume(job.id) }
                            IconBtn(Icons.Filled.Close, "cancel_${job.id}") { onCancel(job.id) }
                        }
                        DownloadStatus.FAILED -> {
                            IconBtn(Icons.Filled.Refresh, "retry_${job.id}") { onRetry(job.id) }
                            IconBtn(Icons.Filled.Close, "remove_${job.id}") { onRemove(job.id) }
                        }
                        DownloadStatus.QUEUED -> IconBtn(Icons.Filled.Close, "cancel_${job.id}") { onCancel(job.id) }
                        DownloadStatus.COMPLETED -> {
                            IconBtn(Icons.AutoMirrored.Filled.OpenInNew, "open_${job.id}") { onOpen(job) }
                            IconBtn(Icons.Filled.Close, "remove_${job.id}") { onRemove(job.id) }
                        }
                        DownloadStatus.CANCELLED -> IconBtn(Icons.Filled.Close, "remove_${job.id}") { onRemove(job.id) }
                    }
                }
            }
        }
        if (running) {
            SieveProgress(job.progress.fraction)
            val pct = job.progress.fraction?.let { "${(it * 100).toInt()}%" } ?: "—"
            val meta = listOfNotNull(pct, job.progress.speed, job.progress.eta?.let { "$it left" }).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        if (job.status == DownloadStatus.FAILED && !job.error.isNullOrBlank()) {
            // job.error is yt-dlp's raw ERROR text (kept raw so retry classification sees real signals).
            Text(
                ErrorHumanizer.humanize(job.error),
                style = MaterialTheme.typography.labelSmall, color = cs.error,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("error_${job.id}"),
            )
            // A failed playlist keeps the entries that finished (QueueManager.keepFinishedFiles). Only a Uri is a
            // saved location: while a job runs, filePath holds a work-dir path that must not read as "saved".
            if (job.hasSavedOutput) {
                Text(
                    "Finished files were saved",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.testTag("saved_${job.id}"),
                )
            }
        }
    }
}

@Composable
private fun StateChip(job: QueueJob) {
    val (label, kind) = when (job.status) {
        DownloadStatus.QUEUED -> "Queued" to ChipKind.NEUTRAL
        DownloadStatus.PREPARING -> "Preparing" to ChipKind.ACCENT
        DownloadStatus.RUNNING -> when (job.progress.phase) {
            Phase.POSTPROCESS -> "Processing" to ChipKind.ACCENT
            Phase.TRANSCODING -> "Transcoding" to ChipKind.ACCENT
            else -> "Downloading" to ChipKind.ACCENT
        }
        DownloadStatus.PAUSED -> "Paused" to ChipKind.WARN
        DownloadStatus.COMPLETED -> "Done" to ChipKind.GOOD
        DownloadStatus.FAILED -> "Failed" to ChipKind.BAD
        DownloadStatus.CANCELLED -> "Cancelled" to ChipKind.NEUTRAL
    }
    SieveChip(label, kind, leadingDot = kind == ChipKind.ACCENT)
}

@Composable
private fun IconBtn(icon: ImageVector, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, cs.outline, RoundedCornerShape(8.dp))
            .testTag(tag)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = tag, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}
