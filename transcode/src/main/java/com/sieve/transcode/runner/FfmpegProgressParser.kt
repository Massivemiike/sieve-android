package com.sieve.transcode.runner

/** One `progress=` block emitted by ffmpeg `-progress pipe:1`. */
data class FfmpegProgress(
    val outTimeUs: Long,
    val percent: Double?,
    val speed: Double?,
    val speedRaw: String?,
    val frame: Long? = null,
    val fps: Double? = null,
    val bitrate: String? = null,
    val totalSize: Long? = null,
    val isEnd: Boolean = false,
)

/**
 * Parses ffmpeg's `-progress` key/value stream. **Distinct** from the yt-dlp progress parser.
 *
 * Two load-bearing facts:
 * - `out_time_ms` is a misnomer — ffmpeg emits **microseconds** there, so percent divides by
 *   1_000_000, never 1000.
 * - Read **stdout only**; never merge stderr into this stream.
 *
 * [onChunk] tolerates arbitrary read boundaries (a key/value line split across two reads is carried).
 * The parser emits `outTimeUs == 0` startup blocks; filtering those is the runner's job (Task 17).
 */
class FfmpegProgressParser(private val totalDurationSec: Double?) {

    private val carry = StringBuilder()
    private val pending = LinkedHashMap<String, String>()

    fun onChunk(chunk: String): List<FfmpegProgress> {
        carry.append(chunk)
        val out = ArrayList<FfmpegProgress>()
        var idx = carry.indexOf("\n")
        while (idx >= 0) {
            val line = carry.substring(0, idx)
            carry.delete(0, idx + 1)
            onLine(line)?.let { out += it }
            idx = carry.indexOf("\n")
        }
        return out
    }

    fun onLine(line: String): FfmpegProgress? {
        val trimmed = line.trim()
        val eq = trimmed.indexOf('=')
        if (eq < 0) return null
        val key = trimmed.substring(0, eq).trim()
        val value = trimmed.substring(eq + 1).trim()
        if (key != "progress") {
            pending[key] = value
            return null
        }
        val outTimeUs = parseOutTimeUs(pending) ?: 0L
        val speedRaw = pending["speed"]
        val progress = FfmpegProgress(
            outTimeUs = outTimeUs,
            percent = percentOf(outTimeUs, totalDurationSec),
            speed = parseSpeed(speedRaw),
            speedRaw = speedRaw,
            frame = pending["frame"]?.toLongOrNull(),
            fps = pending["fps"]?.toDoubleOrNull(),
            bitrate = pending["bitrate"],
            totalSize = pending["total_size"]?.toLongOrNull(),
            isEnd = value == "end",
        )
        pending.clear()
        return progress
    }

    companion object {
        fun parseSpeed(raw: String?): Double? {
            if (raw.isNullOrBlank()) return null
            return raw.trim().removeSuffix("x").trim().toDoubleOrNull()
        }

        /** Precedence: out_time_us → out_time_ms (already µs) → timecode out_time. */
        fun parseOutTimeUs(keys: Map<String, String>): Long? {
            keys["out_time_us"]?.toLongOrNull()?.let { return it }
            keys["out_time_ms"]?.toLongOrNull()?.let { return it }
            keys["out_time"]?.let { return parseTimecodeUs(it) }
            return null
        }

        /** `HH:MM:SS.ffffff` → microseconds. Fractional part is padded/truncated to 6 digits. */
        fun parseTimecodeUs(tc: String): Long? {
            val parts = tc.trim().split(":")
            if (parts.size != 3) return null
            val h = parts[0].toLongOrNull() ?: return null
            val m = parts[1].toLongOrNull() ?: return null
            val sec = parts[2].split(".")
            val s = sec[0].toLongOrNull() ?: return null
            val frac = if (sec.size > 1) (sec[1] + "000000").substring(0, 6).toLongOrNull() ?: 0L else 0L
            return (h * 3600 + m * 60 + s) * 1_000_000 + frac
        }

        fun percentOf(outTimeUs: Long, totalSec: Double?): Double? =
            if (totalSec != null && totalSec > 0) minOf(outTimeUs / 1_000_000.0 / totalSec, 1.0) else null
    }
}
