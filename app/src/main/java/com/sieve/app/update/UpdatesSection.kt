package com.sieve.app.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sieve.app.ui.common.SectionLabel

@Composable
fun UpdatesSection() {
    val context = LocalContext.current
    val vm: UpdateViewModel = viewModel(factory = viewModelFactory { initializer { UpdateViewModel.from(context) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val installer = remember(context) { ApkInstaller(context) }
    UpdatesSectionContent(
        state = state,
        canInstall = installer.canInstall(),
        onCheck = vm::checkNow,
        onInstall = { m -> vm.downloadAndInstall(m) },
        onGrantInstall = { runCatching { context.startActivity(installer.requestInstallPermissionIntent()) } },
    )
}

@Composable
fun UpdatesSectionContent(
    state: UpdateUiState,
    canInstall: Boolean,
    onCheck: () -> Unit,
    onInstall: (UpdateManifest) -> Unit,
    onGrantInstall: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column {
        SectionLabel("Updates")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(cs.surface)
                .border(1.dp, cs.outline, RoundedCornerShape(13.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val status = state.status
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("App updates", style = MaterialTheme.typography.bodyMedium)
                    val sub = when {
                        state.checking -> "Checking…"
                        status is UpdateStatus.Available -> "v${status.manifest.versionName} available"
                        status is UpdateStatus.UpToDate -> "Up to date"
                        else -> "Tap to check"
                    }
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.testTag("update_status"))
                }
                if (state.checking) {
                    CircularProgressIndicator(Modifier.padding(end = 4.dp), strokeWidth = 2.dp)
                } else if (status !is UpdateStatus.Available) {
                    OutlinedButton(onClick = onCheck, modifier = Modifier.testTag("update_check")) { Text("Check") }
                }
            }

            if (status is UpdateStatus.Available) {
                if (status.manifest.changelog.isNotBlank()) {
                    Text(status.manifest.changelog, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                if (!canInstall) {
                    OutlinedButton(onClick = onGrantInstall, modifier = Modifier.fillMaxWidth().testTag("update_grant")) {
                        Text("Allow installing apps")
                    }
                }
                OutlinedButton(
                    onClick = { onInstall(status.manifest) },
                    enabled = !state.downloading,
                    modifier = Modifier.fillMaxWidth().testTag("update_install"),
                ) {
                    Text(if (state.downloading) "Downloading…" else "Download & install")
                }
                if (state.downloading) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                }
            }

            state.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.error) }
        }
    }
}
