package com.sieve.engine.parse

import com.sieve.engine.model.VideoInfo

class AnalyzeException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
        return info
    }
}

/** A result with only storyboard/mhtml formats (or none) usually means auth is needed. */
object StoryboardDetector {
    fun hasOnlyStoryboards(info: VideoInfo): Boolean {
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
