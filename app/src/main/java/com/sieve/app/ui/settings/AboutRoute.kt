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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sieve.app.ui.common.ChipKind
import com.sieve.app.ui.common.SectionLabel
import com.sieve.app.ui.common.SieveChip
import com.sieve.app.ui.theme.MonoFamily

@Composable
fun AboutRoute() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"
    }
    Scaffold(topBar = {
        Text("About", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
    }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.size(10.dp))
                    Text("Sieve for Android", style = MaterialTheme.typography.titleLarge)
                    Text("Version $version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SieveChip("Free", ChipKind.GOOD)
                        SieveChip("Open source", ChipKind.ACCENT)
                        SieveChip("GPLv3", ChipKind.NEUTRAL)
                    }
                }
            }

            item { SectionLabel("License compliance") }
            item {
                Text(
                    "Sieve links GPL-licensed components at runtime — a self-built full-GPL FFmpeg and " +
                        "youtubedl-android (yt-dlp) — so the whole app is licensed under the GNU GPL v3.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { LicenseCard("GNU GPL v3 (FFmpeg + youtubedl-android)", "licenses/GPL.txt", "license_gpl") }
            item { LicenseCard("yt-dlp (Unlicense / public domain)", "licenses/YT_DLP_LICENSE.txt", "license_ytdlp") }
            item { LicenseCard("FFmpeg — written offer for source", "licenses/FFMPEG_SOURCE.txt", "license_ffsrc") }
            item { LicenseCard("Inter & JetBrains Mono (SIL OFL)", "licenses/OFL_Fonts.txt", "license_fonts") }

            item {
                Text(
                    "Powered by yt-dlp and FFmpeg. Not affiliated with any content platform.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun LicenseCard(title: String, assetPath: String, tag: String) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surface).border(1.dp, cs.outline, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.testTag(tag).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = cs.onSurfaceVariant)
        }
        if (expanded) {
            val text by produceState(initialValue = "Loading…", assetPath) {
                value = runCatching { context.assets.open(assetPath).bufferedReader().use { it.readText() } }
                    .getOrDefault("(license text unavailable)")
            }
            Text(
                text,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                color = cs.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(12.dp),
            )
        }
    }
}
