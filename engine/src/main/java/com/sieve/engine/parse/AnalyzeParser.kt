package com.sieve.engine.parse

import com.sieve.engine.model.VideoInfo

/** `stderr` carries yt-dlp's full stderr when the failure came from a non-zero exit (null otherwise). */
class AnalyzeException(message: String, cause: Throwable? = null, val stderr: String? = null) : Exception(message, cause)

/** Parses `yt-dlp -J` stdout into VideoInfo, gating on id || title || a present "formats" key. */
object AnalyzeParser {
    fun parse(stdout: String): VideoInfo {
        if (stdout.isBlank()) throw AnalyzeException("empty analyze output")
        val info = try {
            analyzeJson.decodeFromString<VideoInfo>(stdout)
        } catch (e: Exception) {
            throw AnalyzeException("failed to parse analyze JSON", e)
        }
        // kotlinx maps both absent and [] to emptyList, so detect a present-but-empty
        // formats key by scanning the raw string.
        val gate = !info.id.isNullOrBlank() || !info.title.isNullOrBlank() || stdout.contains("\"formats\"")
        if (!gate) throw AnalyzeException("analyze output missing id/title/formats")
        // A playlist/channel that lists nothing (e.g. every entry unavailable) has nothing to download.
        if (info.isPlaylist && info.entries.isEmpty()) throw AnalyzeException("This link has no downloadable videos.")
        return info
    }
}

/** A result with only storyboard/mhtml formats (or none) usually means auth is needed. */
object StoryboardDetector {
    fun hasOnlyStoryboards(info: VideoInfo): Boolean {
        // Flat playlists carry no per-entry formats; that is not a degraded extraction.
        if (info.isPlaylist) return false
        if (info.formats.isEmpty()) return true
        return info.formats.all { it.isStoryboard || it.protocol == "mhtml" }
    }
}

object AnalyzeError {
    fun extract(stderr: String, code: Int?): String {
        val lines = stderr.split("\n")
        lines.lastOrNull { it.contains("ERROR:") }?.let { return it.trim() }
        lines.lastOrNull { it.isNotBlank() }?.let { return it.trim() }
        return "yt-dlp exited with code $code"
    }
}
