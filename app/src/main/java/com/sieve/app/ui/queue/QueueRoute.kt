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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.sieve.app.ui.common.AppSnackbars
import com.sieve.app.ui.common.ChipKind
import com.sieve.app.ui.common.EmptyState
import com.sieve.app.ui.common.ErrorHumanizer
import com.sieve.app.ui.common.SieveChip
import com.sieve.app.ui.common.SieveProgress
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.Phase
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.displayTitle
import com.sieve.queue.service.OutputIntents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun QueueRoute(
    vm: QueueViewModel = viewModel(factory = viewModelFactory { initializer { QueueViewModel.from() } }),
    reveal: StateFlow<String?> = QueueReveal.id,
    onRevealed: (String) -> Unit = QueueReveal::consume,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val revealJobId by reveal.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbars = remember { AppSnackbars() }
    val scope = rememberCoroutineScope()
    QueueScreen(
        state, vm::pause, vm::resume, vm::retry, vm::cancel,
        onRemove = vm::remove,
        onClearFinished = vm::clearFinished,
        onResumeAllRestored = vm::resumeAllHeld,
        onDismissRestored = vm::dismissRestore,
        onOpen = { job ->
            if (!OutputIntents.open(ctx, job.filePath)) scope.launch { snackbars.show("Can't open this file") }
        },
        snackbarHost = snackbars.state,
        revealJobId = revealJobId,
        onRevealed = onRevealed,
    )
}

/**
 * [revealJobId] is the row the user just added ([QueueReveal]): the list is oldest-first, so on a long queue it sits below
 * the fold. It is scrolled into view once, then [onRevealed] says the request is done.
 */
@Composable
fun QueueScreen(
    state: QueueUiState,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit = {},
    onClearFinished: () -> Unit = {},
    onResumeAllRestored: () -> Unit = {},
    onDismissRestored: () -> Unit = {},
    onOpen: (QueueJob) -> Unit = {},
    snackbarHost: SnackbarHostState = remember { SnackbarHostState() },
    revealJobId: String? = null,
    onRevealed: (String) -> Unit = {},
) {
    // Hoisted out of the list branch below: the empty state swaps the list out, and the reveal needs the same state throughout.
    val listState = rememberLazyListState()
    RevealAddedRow(listState, state.jobs, revealJobId, onRevealed)
    Scaffold(
        topBar = {
            Column {
                QueueTopBar(state, onClearFinished)
                if (state.showRestoreBanner) RestoredBanner(state.restoredHeld, onResumeAllRestored, onDismissRestored)
            }
        },
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
                state = listState,
                modifier = Modifier.padding(padding).fillMaxWidth().testTag("queue_list"),
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

/**
 * Brings the row [requestedId] into view, once, then calls [onHandled]. Windows' Queue page does the same for the active
 * rows (`scrollIntoView`); here the trigger is the user's own add. It does not fight the user:
 *  - the queue appends the row a moment after the tap, so it may not be in [jobs] yet; if the list is scrolled while we wait,
 *    the user has taken over and the request is dropped;
 *  - the scroll is an ordinary (not user-input priority) one, so a drag or fling pre-empts it and the request is dropped too;
 *  - a request is handled at most once, so a recreated screen or a later visit to the tab never re-scrolls to an old row.
 * A row that is already fully on screen is left where it is.
 */
@Composable
private fun RevealAddedRow(
    listState: LazyListState,
    jobs: List<QueueJob>,
    requestedId: String?,
    onHandled: (String) -> Unit,
) {
    val latestJobs by rememberUpdatedState(jobs)
    val latestOnHandled by rememberUpdatedState(onHandled)
    LaunchedEffect(requestedId) {
        val id = requestedId ?: return@LaunchedEffect
        val startIndex = listState.firstVisibleItemIndex
        val startOffset = listState.firstVisibleItemScrollOffset
        val index = snapshotFlow { latestJobs.indexOfFirst { it.id == id } }.first { it >= 0 }
        val userScrolled = listState.firstVisibleItemIndex != startIndex ||
            listState.firstVisibleItemScrollOffset != startOffset || listState.isScrollInProgress
        if (!userScrolled && !listState.isFullyVisible(index)) {
            try {
                listState.animateScrollToItem(index)
            } catch (e: CancellationException) {
                // Pre-empted by the user's own scroll (the coroutine is still active): they win. If the screen itself went away,
                // the request stays pending for the next time the Queue is shown.
                if (!isActive) throw e
            }
        }
        latestOnHandled(id)
    }
}

private fun LazyListState.isFullyVisible(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
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

/**
 * Shown once after an upgrade from v1.0.3 or older: the unfinished downloads that older builds saved but never
 * restored are back, paused, so none of them starts by itself. "Dismiss" only hides this; the rows stay paused.
 */
@Composable
private fun RestoredBanner(count: Int, onResumeAll: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(13.dp)).background(cs.surface)
            .border(1.dp, cs.outline, RoundedCornerShape(13.dp)).testTag("restore_banner")
            .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(RestoredCopy.title(count), style = MaterialTheme.typography.titleSmall)
        Text(RestoredCopy.EXPLANATION, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("restore_dismiss")) { Text("Dismiss") }
            TextButton(onClick = onResumeAll, modifier = Modifier.testTag("restore_resume_all")) { Text("Resume all") }
        }
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
                    job.displayTitle,
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
        rowProgress(job)?.let { rp ->
            SieveProgress(rp.fraction, Modifier.testTag("bar_${job.id}"))
            Text(
                rp.meta, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                modifier = Modifier.testTag("progress_${job.id}"),
            )
        }
        if (job.status == DownloadStatus.FAILED && !job.error.isNullOrBlank()) {
            // job.error is yt-dlp's raw ERROR text (kept raw so retry classification sees real signals); the humanizer words it.
            // Two lines: the hint after the dash says what to do, and one line cuts it off.
            Text(
                ErrorHumanizer.humanize(job),
                style = MaterialTheme.typography.labelSmall, color = cs.error,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
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
