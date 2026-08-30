package com.sieve.engine.parse

import com.sieve.engine.model.DownloadProgress
import com.sieve.engine.model.Sentinels

/**
 * Ports the desktop progress parsing. Handles BOTH the `--progress-template`
 * pipe format (A) and the default `[download] X% of Y at Z ETA W` line (B).
 * Preserves quirks: only Format A guards NaN; `~`-estimated-size lines don't
 * match Format B; `[Metadata]` yields a path but not a postprocess; the ANSI
 * strip deliberately leaves bare ESC bytes.
 */
object ProgressParser {
    private val formatB =
        Regex("""\[download]\s+([\d.]+)%\s+of\s+[\d.]+\S+\s+at\s+(\S+)\s+ETA\s+(\S+)""")
    private val ansi = Regex("""\[[0-9;]*m""")
    private val dest =
        Regex("""(?:\[download]|\[Merger]|\[Metadata]|\[ExtractAudio]|\[EmbedThumbnail]).*?Destination:\s*(.+)""")
    private val merging = Regex("""Merging formats into "(.+)"""")
    private val already = Regex("""\[download]\s+(.+) has already been downloaded""")
    private val postProc = Regex("""\[(Merger|ExtractAudio|EmbedThumbnail)]""")
    private val sizeRe = Regex("""of\s+~?([\d.]+)\s*(GiB|MiB|KiB|B)""")

    fun parseProgress(line: String): DownloadProgress? {
        // Format A: pipe template "percent|speed|eta|frag"
        val parts = line.split("|")
        if (parts.size == 4 && parts[0].contains("%")) {
            val pct = parts[0].replace("%", "").trim().toFloatOrNull()
                ?.let { if (it.isNaN()) 0f else it / 100f } ?: 0f
            fun dash(s: String) = s.trim().ifEmpty { Sentinels.DASH }
            return DownloadProgress(pct, dash(parts[1]), dash(parts[2]), dash(parts[3]))
        }
        // Format B: default line
        formatB.find(line)?.let { m ->
            val pct = (m.groupValues[1].toFloatOrNull() ?: 0f) / 100f
            return DownloadProgress(pct, m.groupValues[2], m.groupValues[3], Sentinels.DASH)
        }
        return null
    }

    fun parseFilePath(line: String): String? {
        dest.find(line)?.let { return it.groupValues[1].trim() }
        merging.find(line)?.let { return it.groupValues[1] }
        already.find(line)?.let { return it.groupValues[1] }
        return null
    }

    fun isPostProcess(line: String): Boolean = postProc.containsMatchIn(line)

    /** "of 1.23GiB" → "1.2 GB" (relabel iB→B, one decimal). */
    fun parseSize(line: String): String? {
        val m = sizeRe.find(line) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].replace("iB", "B")
        return "%.1f %s".format(java.util.Locale.ROOT, n, unit)
    }

    fun cleanLogLine(line: String): String {
        val stripped = line.replace(ansi, "")
        return if (stripped.length > 500) stripped.take(500) + "… (truncated)" else stripped
    }

    fun isProgressLine(line: String): Boolean = line.contains("[download]") && line.contains("%")
}
