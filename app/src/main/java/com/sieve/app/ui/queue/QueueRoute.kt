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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.sieve.app.ui.common.SieveChip
import com.sieve.app.ui.common.SieveProgress
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.Phase
import com.sieve.queue.core.QueueJob

@Composable
fun QueueRoute(
    vm: QueueViewModel = viewModel(factory = viewModelFactory { initializer { QueueViewModel.from() } }),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    QueueScreen(state, vm::pause, vm::resume, vm::retry, vm::cancel)
}

@Composable
fun QueueScreen(
    state: QueueUiState,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    Scaffold(topBar = { QueueTopBar(state) }) { padding ->
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
                    JobRow(job, onPause, onResume, onRetry, onCancel)
                }
            }
        }
    }
}

@Composable
private fun QueueTopBar(state: QueueUiState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("Queue", style = MaterialTheme.typography.titleLarge)
        Text(
            "${state.summary.running} active · ${state.summary.queued} queued",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            IconBtn(Icons.Filled.Close, "cancel_${job.id}") { onCancel(job.id) }
                        }
                        DownloadStatus.QUEUED -> IconBtn(Icons.Filled.Close, "cancel_${job.id}") { onCancel(job.id) }
                        DownloadStatus.COMPLETED -> IconBtn(Icons.Filled.Folder, "open_${job.id}") { }
                        DownloadStatus.CANCELLED -> {}
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
