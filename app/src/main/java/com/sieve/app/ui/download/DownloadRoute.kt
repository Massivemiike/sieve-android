package com.sieve.app.ui.download

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.sieve.app.ui.common.ChipKind
import com.sieve.app.ui.common.EmptyState
import com.sieve.app.ui.common.SectionLabel
import com.sieve.app.ui.common.SieveChip
import com.sieve.app.ui.theme.MonoFamily
import com.sieve.engine.model.VideoInfo

@Composable
fun DownloadRoute(
    vm: DownloadViewModel = viewModel(factory = viewModelFactory { initializer { DownloadViewModel.from() } }),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    DownloadScreen(
        state = state,
        onUrlChange = vm::onUrlChange,
        onAnalyze = vm::analyze,
        onSelectPreset = vm::selectPreset,
        onDownload = vm::download,
    )
}

@Composable
fun DownloadScreen(
    state: DownloadUiState,
    onUrlChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onDownload: () -> Unit,
) {
    Scaffold(topBar = { Wordmark() }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { UrlCard(state, onUrlChange, onAnalyze) }

            val showHero = state.analyzed == null && state.url.isBlank() && !state.analyzing && state.error == null
            if (showHero) {
                item {
                    Box(Modifier.fillMaxWidth().height(220.dp)) {
                        EmptyState(
                            icon = Icons.Filled.Download,
                            title = "Paste a link to get started",
                            subtitle = "YouTube, Vimeo, SoundCloud, Twitch, and 1000+ other sites.",
                        )
                    }
                }
            }

            state.error?.let { err ->
                item { ErrorBanner(err, onAnalyze) }
            }
            state.analyzed?.let { info ->
                item { VideoInfoCard(info) }
            }

            item { SectionLabel("Format", state.presets.size, Modifier.padding(top = 6.dp)) }
            items(state.presets.chunked(2).size) { rowIndex ->
                val row = state.presets.chunked(2)[rowIndex]
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { p ->
                        Box(Modifier.weight(1f)) {
                            PresetCard(p, selected = p.id == state.selectedPresetId) { onSelectPreset(p.id) }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            item {
                Button(
                    onClick = onDownload,
                    enabled = state.canDownload,
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 6.dp).testTag("download_btn"),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Download · ${DownloadPresets.byId(state.selectedPresetId).label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Wordmark() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(17.dp))
        }
        Text("Sieve", style = MaterialTheme.typography.titleLarge)
        Text(
            "0.1.0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun UrlCard(state: DownloadUiState, onUrlChange: (String) -> Unit, onAnalyze: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.url,
                onValueChange = onUrlChange,
                modifier = Modifier.weight(1f).testTag("url_field"),
                placeholder = { Text("Paste a link to analyze") },
                singleLine = true,
                textStyle = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp),
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onAnalyze() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
            Button(
                onClick = onAnalyze,
                enabled = state.url.isNotBlank() && !state.analyzing,
                modifier = Modifier.height(44.dp).testTag("analyze_btn"),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                if (state.analyzing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("Analyze")
                }
            }
        }
        val chipText = when {
            state.analyzing -> "Analyzing…"
            state.analyzed != null -> "${state.analyzed.extractor ?: "site"} detected"
            state.hostname != null -> state.hostname!!
            else -> null
        }
        if (chipText != null) {
            SieveChip(chipText, if (state.analyzed != null) ChipKind.ACCENT else ChipKind.NEUTRAL)
        } else {
            Text(
                "YouTube, Vimeo, SoundCloud, Twitch, and 1000+ other sites.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Analysis failed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("You can still download — pick a preset and tap Download.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VideoInfoCard(info: VideoInfo) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        AsyncImage(
            model = info.thumbnail,
            contentDescription = null,
            modifier = Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(info.title ?: "Untitled", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (info.displayChannel.isNotBlank()) {
                Text(info.displayChannel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val meta = buildList {
                info.duration?.let { add(formatDuration(it.toLong())) }
                info.extractor?.let { add(it) }
                if (info.isPlaylist) add("${info.playlistCount ?: info.entries.size} videos")
            }.joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PresetCard(preset: DownloadPreset, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) cs.primaryContainer else cs.surface)
            .border(1.dp, if (selected) cs.primary else cs.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).testTag("preset_${preset.id}").padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                .background(if (selected) cs.primary.copy(alpha = 0.18f) else cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(preset.icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (selected) cs.primary else cs.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(preset.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (preset.badge != null) {
                    SieveChip(preset.badge, ChipKind.ACCENT)
                }
            }
            Text(preset.desc, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(50)).background(cs.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(12.dp))
            }
        }
    }
}

private fun formatDuration(sec: Long): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
