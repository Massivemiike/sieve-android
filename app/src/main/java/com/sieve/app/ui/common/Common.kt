package com.sieve.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ChipKind { NEUTRAL, ACCENT, GOOD, WARN, BAD }

@Composable
fun SieveChip(text: String, kind: ChipKind = ChipKind.NEUTRAL, leadingDot: Boolean = false, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val fg = when (kind) {
        ChipKind.NEUTRAL -> cs.onSurfaceVariant
        ChipKind.ACCENT, ChipKind.WARN -> cs.primary
        ChipKind.GOOD -> cs.tertiary
        ChipKind.BAD -> cs.error
    }
    val bg = when (kind) {
        ChipKind.NEUTRAL -> cs.surfaceContainerHigh
        else -> fg.copy(alpha = 0.12f)
    }
    val border = when (kind) {
        ChipKind.NEUTRAL -> cs.outline
        else -> fg.copy(alpha = 0.3f)
    }
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leadingDot) Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium.copy(fontFamily = com.sieve.app.ui.theme.MonoFamily))
    }
}

@Composable
fun SieveProgress(fraction: Float?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val m = modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(999.dp))
    if (fraction == null) {
        LinearProgressIndicator(modifier = m, color = cs.primary, trackColor = cs.surfaceContainerHigh)
    } else {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = m, color = cs.primary, trackColor = cs.surfaceContainerHigh,
        )
    }
}

@Composable
fun SectionLabel(text: String, count: Int? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.2.sp,
        )
        if (count != null) {
            Text("· $count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
