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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sieve.app.ui.common.ChipKind
import com.sieve.app.ui.common.SectionLabel
import com.sieve.app.ui.common.SieveChip
import com.sieve.app.ui.common.rememberOpenDocument
import com.sieve.app.ui.common.rememberOpenDocumentTree
import com.sieve.app.settings.CookieAge
import com.sieve.app.settings.CookiesFile
import com.sieve.app.settings.CookiesInfo
import com.sieve.app.settings.NetworkSettings
import com.sieve.app.ui.download.DownloadPresets
import com.sieve.app.ui.theme.AccentSwatches
import com.sieve.app.ui.theme.ThemeMode
import com.sieve.app.ui.theme.accentFromHex
import com.sieve.queue.core.QueueLimits

@Composable
fun SettingsRoute(
    onOpenAbout: () -> Unit = {},
    vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel.from() } }),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val grant = rememberOpenDocumentTree { uri -> vm.setOutputTree(uri.toString()) }
    // "*/*": file managers label a cookies.txt text/plain, octet-stream or nothing at all; import validates the content.
    val pickCookies = rememberOpenDocument(arrayOf("*/*")) { uri -> vm.importCookies(uri.toString()) }
    SettingsScreen(
        state, grant, vm::setThemeMode, vm::setAccent, vm::setDefaultPreset, vm::setMaxDownloads, vm::setMaxTranscodes,
        vm::updateEngine, vm::reset, onOpenAbout,
        updatesSlot = { com.sieve.app.update.UpdatesSection() },
        network = NetworkActions(
            onProxy = vm::setProxy, onUserAgent = vm::setUserAgent, onSpeedLimit = vm::setSpeedLimit,
            onPickCookies = pickCookies, onRemoveCookies = vm::removeCookies, onDismissCookiesMessage = vm::dismissCookiesMessage,
        ),
    )
}

/** What the Network rows can do; the defaults keep previews and tests that don't care compiling. */
data class NetworkActions(
    val onProxy: (String?) -> Unit = {},
    val onUserAgent: (String?) -> Unit = {},
    val onSpeedLimit: (String?) -> Unit = {},
    val onPickCookies: () -> Unit = {},
    val onRemoveCookies: () -> Unit = {},
    val onDismissCookiesMessage: () -> Unit = {},
)

private enum class NetField { PROXY, USER_AGENT, SPEED }

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
    network: NetworkActions = NetworkActions(),
) {
    var editing by remember { mutableStateOf<NetField?>(null) }
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
                    RowStepper("Max downloads", state.app.maxDownloads, QueueLimits.DOWNLOADS.first, QueueLimits.DOWNLOADS.last, onMaxDownloads, "maxdl")
                    Divider()
                    RowItem("Default format", DownloadPresets.byId(state.app.defaultPresetId).label)
                }
            }

            item { SectionLabel("Network") }
            item {
                Group {
                    EditRow("Proxy", state.app.proxy, "Not set", "proxy_row") { editing = NetField.PROXY }
                    Divider()
                    EditRow("User-agent", state.app.userAgent, "Default", "ua_row") { editing = NetField.USER_AGENT }
                    Divider()
                    EditRow("Speed limit", state.app.speedLimit?.let { "$it/s" }, "Unlimited", "speed_row") { editing = NetField.SPEED }
                    Divider()
                    CookiesRow(state.cookies, state.cookiesMessage, network)
                }
            }

            item { SectionLabel("Transcode") }
            item { Group { RowStepper("Max transcodes", state.app.maxTranscodes, QueueLimits.TRANSCODES.first, QueueLimits.TRANSCODES.last, onMaxTranscodes, "maxtx") } }

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

    when (editing) {
        NetField.PROXY -> TextEditDialog(
            "Proxy", "Applies to new downloads. Empty = direct connection.", "socks5://127.0.0.1:1080",
            state.app.proxy.orEmpty(), NetworkSettings::proxyError, network.onProxy, onDismiss = { editing = null },
        )
        NetField.USER_AGENT -> TextEditDialog(
            "User-agent", "Applies to new downloads. Empty = yt-dlp default.", "(default)",
            state.app.userAgent.orEmpty(), NetworkSettings::userAgentError, network.onUserAgent, onDismiss = { editing = null },
        )
        NetField.SPEED -> TextEditDialog(
            "Speed limit", "Per download, e.g. 2M or 500K. Empty = unlimited.", "0 (unlimited)",
            state.app.speedLimit.orEmpty(), NetworkSettings::speedLimitError, network.onSpeedLimit, onDismiss = { editing = null },
        )
        null -> Unit
    }
}

/** A label with its current value; tapping opens the editor. Long values (a user-agent) ellipsize. */
@Composable
private fun EditRow(label: String, value: String?, empty: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Text(
            value ?: empty, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f),
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** Edits one text setting. Save is only enabled for acceptable text; blank clears the setting. */
@Composable
private fun TextEditDialog(
    title: String,
    hint: String,
    placeholder: String,
    initial: String,
    validate: (String) -> String?,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val error = validate(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, isError = error != null,
                placeholder = { Text(placeholder) },
                supportingText = { Text(error ?: hint) },
                modifier = Modifier.fillMaxWidth().testTag("edit_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim().ifEmpty { null }); onDismiss() }, enabled = error == null, modifier = Modifier.testTag("edit_save")) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (initial.isNotBlank()) TextButton(onClick = { onSave(null); onDismiss() }, modifier = Modifier.testTag("edit_clear")) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Import / replace / remove the cookies.txt, with the desktop's staleness warning. */
@Composable
private fun CookiesRow(info: CookiesInfo?, message: String?, network: NetworkActions) {
    val cs = MaterialTheme.colorScheme
    val ageDays = remember(info) { info?.ageDays(System.currentTimeMillis()) }
    val stale = ageDays != null && CookieAge.of(ageDays) != CookieAge.FRESH
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cookies file", style = MaterialTheme.typography.bodyMedium)
                Text(
                    info?.let { CookiesFile.statusLine(it) } ?: "Not set",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.testTag("cookies_status"),
                )
            }
            OutlinedButton(onClick = network.onPickCookies, modifier = Modifier.testTag("cookies_import")) {
                Text(if (info == null) "Import" else "Replace")
            }
            if (info != null) {
                TextButton(onClick = network.onRemoveCookies, modifier = Modifier.testTag("cookies_remove")) { Text("Remove") }
            }
        }
        if (info == null) {
            Text(
                "Netscape cookies.txt for age-restricted or login-only videos. Applies to new downloads.",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
        if (ageDays != null) {
            Text(
                CookiesFile.ageLabel(ageDays), style = MaterialTheme.typography.labelSmall,
                color = if (stale) cs.error else cs.onSurfaceVariant, modifier = Modifier.testTag("cookies_age"),
            )
            CookiesFile.warning(ageDays)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = cs.error, modifier = Modifier.testTag("cookies_warning"))
            }
        }
        if (message != null) {
            Text(
                message, style = MaterialTheme.typography.labelSmall, color = cs.error,
                modifier = Modifier.clickable(onClick = network.onDismissCookiesMessage).testTag("cookies_message"),
            )
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
