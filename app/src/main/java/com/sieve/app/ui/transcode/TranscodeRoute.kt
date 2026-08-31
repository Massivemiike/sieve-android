package com.sieve.app.ui.transcode

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sieve.app.ui.common.ChipKind
import com.sieve.app.ui.common.SectionLabel
import com.sieve.app.ui.common.SieveChip
import com.sieve.app.ui.common.rememberOpenDocument
import com.sieve.transcode.detect.EncoderKind
import com.sieve.transcode.model.TranscodePreset

@Composable
fun TranscodeRoute(
    vm: TranscodeViewModel = viewModel(factory = viewModelFactory { initializer { TranscodeViewModel.from() } }),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val handoff by com.sieve.app.ui.common.SharedTranscodeSource.src.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(handoff) {
        handoff?.let {
            vm.addSource(SourceInput(it.uri, it.name, null))
            com.sieve.app.ui.common.SharedTranscodeSource.consume()
        }
    }
    val pick = rememberOpenDocument(arrayOf("video/*", "audio/*")) { uri ->
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: "media"
        vm.addSource(SourceInput(uri.toString(), name, null))
    }
    TranscodeScreen(state, pick, vm::setMode, vm::setEncoder, vm::selectCategory, vm::selectPreset, vm::setCrf, vm::setNormalize, vm::removeSource, vm::start)
}

@Composable
fun TranscodeScreen(
    state: TranscodeUiState,
    onPickSource: () -> Unit,
    onMode: (TranscodeMode) -> Unit,
    onEncoder: (String) -> Unit,
    onCategory: (com.sieve.transcode.model.PresetCategory?) -> Unit,
    onSelectPreset: (String) -> Unit,
    onCrf: (Int) -> Unit,
    onNormalize: (Boolean) -> Unit,
    onRemoveSource: (String) -> Unit,
    onStart: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Transcode", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick = onPickSource).testTag("source_pick"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, contentDescription = "Add source", tint = cs.onSurfaceVariant)
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth().testTag("transcode_list"),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ModeTabs(state.mode, onMode) }
            item { EncoderRail(state, onEncoder) }
            item { CategoryChips(state, onCategory) }
            item { SourceSection(state, onPickSource, onRemoveSource) }
            item {
                val catLabel = state.selectedCategory?.label ?: "All"
                SectionLabel("Preset — $catLabel", state.visiblePresets.size, Modifier.padding(top = 4.dp))
            }
            items(state.visiblePresets.chunked(2).size) { rowIndex ->
                val row = state.visiblePresets.chunked(2)[rowIndex]
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { p ->
                        Box(Modifier.weight(1f)) { PresetCard(p, p.id == state.selectedPresetId) { onSelectPreset(p.id) } }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item {
                Column(Modifier.padding(top = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Quality (CRF)", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Text("${state.crf}", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                    }
                    Slider(
                        value = state.crf.toFloat(),
                        onValueChange = { onCrf(it.toInt()) },
                        valueRange = 14f..32f,
                        modifier = Modifier.testTag("crf_slider"),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Normalize audio", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Switch(checked = state.normalize, onCheckedChange = onNormalize, modifier = Modifier.testTag("normalize_switch"))
                    }
                }
            }
            item {
                Button(
                    onClick = onStart,
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("start_btn"),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start transcode")
                }
            }
        }
    }
}

@Composable
private fun ModeTabs(mode: TranscodeMode, onMode: (TranscodeMode) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TranscodeMode.entries.forEach { m ->
            val on = m == mode
            Box(
                Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (on) cs.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onMode(m) }.testTag("mode_${m.name.lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(m.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, color = if (on) cs.onSurface else cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EncoderRail(state: TranscodeUiState, onEncoder: (String) -> Unit) {
    Column {
        SectionLabel("Encoder")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            state.encoders.forEach { e ->
                val selected = e.id == state.activeEncoderId
                val kind = when {
                    selected && e.kind == EncoderKind.HARDWARE -> ChipKind.GOOD
                    selected -> ChipKind.ACCENT
                    else -> ChipKind.NEUTRAL
                }
                Box(Modifier.clickable { onEncoder(e.id) }.testTag("encoder_${e.id}")) {
                    SieveChip("${e.label} · ${e.dev}", kind, leadingDot = e.kind == EncoderKind.HARDWARE)
                }
            }
        }
    }
}

@Composable
private fun CategoryChips(state: TranscodeUiState, onCategory: (com.sieve.transcode.model.PresetCategory?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        state.categoryTabs.forEach { tab ->
            val selected = tab.category == state.selectedCategory
            Box(Modifier.clickable { onCategory(tab.category) }.testTag("cat_${tab.label}")) {
                SieveChip("${tab.label} ${tab.count}", if (selected) ChipKind.ACCENT else ChipKind.NEUTRAL)
            }
        }
    }
}

@Composable
private fun SourceSection(state: TranscodeUiState, onPick: () -> Unit, onRemove: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (state.sources.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                .background(cs.surface).border(1.dp, cs.outline, RoundedCornerShape(13.dp))
                .clickable(onClick = onPick).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(26.dp))
            Text("Pick a video to transcode", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.sources.forEach { src ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(cs.surface)
                        .border(1.dp, cs.outline, RoundedCornerShape(11.dp)).padding(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(src.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box(Modifier.size(28.dp).clickable { onRemove(src.uri) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetCard(preset: TranscodePreset, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) cs.primaryContainer else cs.surface)
            .border(1.dp, if (selected) cs.primary else cs.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).testTag("preset_${preset.id}").padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(preset.ext.uppercase(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 5.dp, vertical = 2.dp))
            preset.badge?.let { SieveChip(it, ChipKind.ACCENT) }
            Spacer(Modifier.weight(1f))
            if (selected) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(50)).background(cs.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(11.dp))
                }
            }
        }
        Text(preset.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(preset.sub, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
