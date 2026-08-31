package com.sieve.queue.core

import com.sieve.engine.model.DownloadProgress
import com.sieve.transcode.runner.FfmpegProgress

private val SPEED_RE = Regex("""([\d.]+)\s*(GiB|MiB|KiB)""")
private val BYTES_RE = Regex("""([\d.]+)\s*(GiB|MiB|KiB|GB|MB|KB|B)""", RegexOption.IGNORE_CASE)
private const val DASH = "—"

/** Desktop parseSpeedMiB (downloadStore.ts): normalize a speed label to MiB/s, else 0. */
fun parseSpeedMiB(speed: String?): Float {
    if (speed.isNullOrBlank() || speed == DASH) return 0f
    val m = SPEED_RE.find(speed) ?: return 0f
    val v = m.groupValues[1].toFloatOrNull() ?: return 0f
    return when (m.groupValues[2]) {
        "GiB" -> v * 1024f
        "KiB" -> v / 1024f
        else -> v            // MiB
    }
}

/** Desktop 'parse' (Queue.tsx): accepts IEC (GiB/MiB/KiB) and SI (GB/MB/KB/B) → bytes. */
fun parseBytes(label: String?): Long? {
    if (label.isNullOrBlank() || label == DASH) return null
    val m = BYTES_RE.find(label) ?: return null
    val v = m.groupValues[1].toDoubleOrNull() ?: return null
    val mult = when (m.groupValues[2].uppercase()) {
        "GIB" -> 1024.0 * 1024 * 1024
        "MIB" -> 1024.0 * 1024
        "KIB" -> 1024.0
        "GB" -> 1_000_000_000.0
        "MB" -> 1_000_000.0
        "KB" -> 1_000.0
        else -> 1.0
    }
    return (v * mult).toLong()
}

/** yt-dlp fragment label "i/n" → (index, count); (null,null) for the "—" sentinel. */
private fun parseFragment(frag: String?): Pair<Int?, Int?> {
    if (frag.isNullOrBlank() || frag == DASH) return null to null
    val parts = frag.split("/")
    if (parts.size != 2) return null to null
    return parts[0].trim().toIntOrNull() to parts[1].trim().toIntOrNull()
}

/**
 * Maps the two module progress shapes into [UnifiedProgress].
 *
 * Reconciled to the REAL module types (Task 3 was written against assumed shapes): the engine's
 * [DownloadProgress] carries percent already in 0..1 with `—`-sentinel string fields and a combined
 * `fragment` label (no size); the transcode [FfmpegProgress] already carries a clamped `percent`
 * plus `speedRaw`/`totalSize`. This file is the ONLY place those concrete shapes leak in.
 */
object ProgressMapper {
    fun fromDownload(p: DownloadProgress): UnifiedProgress {
        val (fi, fc) = parseFragment(p.fragment)
        return UnifiedProgress(
            fraction = p.percent.coerceIn(0f, 1f),
            speed = p.speed.takeIf { it != DASH },
            eta = p.eta.takeIf { it != DASH },
            phase = Phase.DOWNLOADING,
            sizeBytes = null,               // engine DownloadProgress carries no size label
            fragmentIndex = fi,
            fragmentCount = fc,
        )
    }

    fun fromFfmpeg(p: FfmpegProgress, totalDurationSec: Double?): UnifiedProgress {
        val frac = p.percent?.toFloat()
            ?: if (totalDurationSec != null && totalDurationSec > 0.0)
                (p.outTimeUs / 1_000_000.0 / totalDurationSec).toFloat() else null
        return UnifiedProgress(
            fraction = frac?.coerceIn(0f, 1f),
            speed = p.speedRaw,
            eta = null,
            phase = Phase.TRANSCODING,
            sizeBytes = p.totalSize,
        )
    }
}
