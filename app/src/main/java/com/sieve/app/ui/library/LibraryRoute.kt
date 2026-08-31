package com.sieve.app.ui.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.sieve.app.ui.common.ChipKind
import com.sieve.app.ui.common.EmptyState
import com.sieve.app.ui.common.SieveChip
import com.sieve.app.ui.common.rememberOpenDocumentTree
import com.sieve.storage.library.LibraryEntry
import com.sieve.storage.library.MediaKind

@Composable
fun LibraryRoute(
    onOpenTranscode: () -> Unit = {},
    vm: LibraryViewModel = viewModel(factory = viewModelFactory { initializer { LibraryViewModel.from() } }),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val grant = rememberOpenDocumentTree { uri -> vm.onGrantTree(uri.toString()) }
    LibraryScreen(
        state = state,
        onGrant = grant,
        onEnter = vm::enter,
        onUp = vm::up,
        onFilter = vm::setFilter,
        onOpen = { entry -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } },
        onDelete = vm::delete,
        onSendToTranscode = { entry -> vm.sendToTranscode(entry); onOpenTranscode() },
    )
}

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onGrant: () -> Unit,
    onEnter: (LibraryEntry) -> Unit,
    onUp: () -> Unit,
    onFilter: (MediaKind) -> Unit,
    onOpen: (LibraryEntry) -> Unit,
    onDelete: (LibraryEntry) -> Unit,
    onSendToTranscode: (LibraryEntry) -> Unit,
) {
    Scaffold(topBar = { LibraryTopBar(state, onGrant, onUp) }) { padding ->
        when {
            state.treeUri == null -> Box(Modifier.padding(padding).fillMaxWidth()) {
                EmptyState(
                    icon = Icons.Filled.Folder,
                    title = "Choose a folder",
                    subtitle = "Grant Sieve a folder to browse your downloads and transcodes.",
                    actionLabel = "Choose folder",
                    onAction = onGrant,
                )
            }
            state.entries.isEmpty() -> Box(Modifier.padding(padding).fillMaxWidth()) {
                EmptyState(icon = Icons.Filled.Folder, title = "Nothing here", subtitle = "This folder has no matching files.")
            }
            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxWidth().testTag("library_list"),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item { FilterChips(state.filter, onFilter) }
                items(state.entries, key = { it.uri }) { entry ->
                    FileRow(entry, onEnter, onOpen, onDelete, onSendToTranscode)
                }
                item {
                    Text(
                        "${state.entries.count { !it.isDir }} files",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryTopBar(state: LibraryUiState, onGrant: () -> Unit, onUp: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.canGoUp) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick = onUp).testTag("lib_up"), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Library", style = MaterialTheme.typography.titleLarge)
            if (state.treeUri != null) {
                Text("Download / ${state.folderName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.treeUri != null) {
            Box(Modifier.clickable(onClick = onGrant).testTag("lib_change")) { SieveChip("Change", ChipKind.NEUTRAL) }
        }
    }
}

@Composable
private fun FilterChips(filter: MediaKind, onFilter: (MediaKind) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf(MediaKind.ALL to "All", MediaKind.VIDEO to "Video", MediaKind.AUDIO to "Audio", MediaKind.IMAGE to "Image").forEach { (kind, label) ->
            Box(Modifier.clickable { onFilter(kind) }.testTag("filter_$label")) {
                SieveChip(label, if (kind == filter) ChipKind.ACCENT else ChipKind.NEUTRAL)
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: LibraryEntry,
    onEnter: (LibraryEntry) -> Unit,
    onOpen: (LibraryEntry) -> Unit,
    onDelete: (LibraryEntry) -> Unit,
    onSendToTranscode: (LibraryEntry) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
            .clickable { if (entry.isDir) onEnter(entry) else onOpen(entry) }
            .testTag("file_${entry.name}").padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(9.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(kindIcon(entry), contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!entry.isDir) {
                Text(
                    listOfNotNull(entry.ext.uppercase().ifBlank { null }, formatSize(entry.size)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
            }
        }
        if (!entry.isDir) {
            Box {
                Box(Modifier.size(30.dp).clickable { menu = true }.testTag("menu_${entry.name}"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen(entry) })
                    DropdownMenuItem(text = { Text("Send to transcode") }, onClick = { menu = false; onSendToTranscode(entry) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete permanently?") },
            text = { Text("${entry.name} will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(entry) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun kindIcon(entry: LibraryEntry): ImageVector = when {
    entry.isDir -> Icons.Filled.Folder
    entry.ext in setOf("m4a", "mp3", "opus", "ogg", "flac", "wav", "aac") -> Icons.Filled.Audiotrack
    entry.ext in setOf("jpg", "jpeg", "png", "webp", "gif") -> Icons.Filled.Image
    else -> Icons.Filled.Movie
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
