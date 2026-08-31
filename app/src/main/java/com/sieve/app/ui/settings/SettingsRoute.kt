package com.sieve.app.ui.settings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sieve.app.ui.common.ChipKind
import com.sieve.app.ui.common.SectionLabel
import com.sieve.app.ui.common.SieveChip
import com.sieve.app.ui.common.rememberOpenDocumentTree
import com.sieve.app.ui.download.DownloadPresets
import com.sieve.app.ui.theme.AccentSwatches
import com.sieve.app.ui.theme.ThemeMode
import com.sieve.app.ui.theme.accentFromHex

@Composable
fun SettingsRoute(
    onOpenAbout: () -> Unit = {},
    vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel.from() } }),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val grant = rememberOpenDocumentTree { uri -> vm.setOutputTree(uri.toString()) }
    SettingsScreen(state, grant, vm::setThemeMode, vm::setAccent, vm::setDefaultPreset, vm::setMaxDownloads, vm::setMaxTranscodes, vm::updateEngine, vm::reset, onOpenAbout, updatesSlot = { com.sieve.app.update.UpdatesSection() })
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onGrant: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onAccent: (String) -> Unit,
    onDefaultPreset: (String) -> Unit,
    onMaxDownloads: (Int) -> Unit,
    onMaxTranscodes: (Int) -> Unit,
    onUpdateEngine: () -> Unit,
    onReset: () -> Unit,
    onOpenAbout: () -> Unit,
    updatesSlot: @Composable () -> Unit = {},
) {
    Scaffold(topBar = {
        Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
    }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth().testTag("settings_list"),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { SectionLabel("Storage") }
            item {
                Group {
                    RowItem("Save location", state.outputTreeUri?.let { "Granted" } ?: "Not set", Modifier.clickable(onClick = onGrant).testTag("save_location"))
                }
            }

            item { SectionLabel("Appearance") }
            item {
                Group {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Theme", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Segmented(ThemeMode.entries, state.app.themeMode, { it.name.lowercase().replaceFirstChar { c -> c.uppercaseChar() } }, onTheme)
                    }
                    Divider()
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Accent", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AccentSwatches.forEach { (_, color) ->
                                val hex = "#%06X".format(0xFFFFFF and color.toArgb())
                                val selected = accentFromHex(state.app.accentHex).toArgb() == color.toArgb()
                                Box(
                                    Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(color)
                                        .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, RoundedCornerShape(6.dp))
                                        .clickable { onAccent(hex) }.testTag("accent_$hex"),
                                )
                            }
                        }
                    }
                }
            }

            item { SectionLabel("Downloads") }
            item {
                Group {
                    RowStepper("Max downloads", state.app.maxDownloads, 1, 10, onMaxDownloads, "maxdl")
                    Divider()
                    RowItem("Default format", DownloadPresets.byId(state.app.defaultPresetId).label)
                }
            }

            item { SectionLabel("Transcode") }
            item { Group { RowStepper("Max transcodes", state.app.maxTranscodes, 1, 4, onMaxTranscodes, "maxtx") } }

            item { SectionLabel("Engine") }
            item {
                Group {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("yt-dlp", style = MaterialTheme.typography.bodyMedium)
                            Text(state.engineVersion ?: "unknown", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = onUpdateEngine, enabled = !state.updating, modifier = Modifier.testTag("update_engine")) {
                            Text(if (state.updating) "Updating…" else "Update")
                        }
                    }
                    Divider()
                    RowItem("ffmpeg", "bundled · full-gpl")
                }
            }

            item { updatesSlot() }

            item { SectionLabel("About") }
            item {
                Group {
                    RowItem("Licenses & version", "GPLv3", Modifier.clickable(onClick = onOpenAbout).testTag("about_row"), chevron = true)
                }
            }

            item {
                OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("reset_btn")) {
                    Text("Reset settings")
                }
            }
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(13.dp)),
    ) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun RowItem(label: String, value: String, modifier: Modifier = Modifier, chevron: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun RowStepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { if (value > min) onChange(value - 1) }.testTag("${tag}_dec"), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Remove, contentDescription = "Less", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("$value", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { if (value < max) onChange(value + 1) }.testTag("${tag}_inc"), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, contentDescription = "More", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { opt ->
            val on = opt == selected
            Box(
                Modifier.clip(RoundedCornerShape(7.dp)).background(if (on) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                    .clickable { onSelect(opt) }.testTag("seg_${label(opt).lowercase()}").padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(label(opt), style = MaterialTheme.typography.labelMedium, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
