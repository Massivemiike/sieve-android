package com.sieve.engine.parse

/**
 * Ports the desktop error extraction + humanization + transient classification
 * (App.tsx:517-530), VERBATIM including the case-sensitive `.includes` triggers
 * and the two preserved footguns: bare `age` matches inside words like "message",
 * and humanize erases its own `throttl` transient signal.
 */
object ErrorMapper {
    /** last "ERROR:" line, else the code fallback (2 tiers — matches the desktop). */
    fun extract(logs: List<String>, exitCode: Int): String =
        logs.lastOrNull { it.contains("ERROR:") } ?: "yt-dlp exited with code $exitCode"

    fun humanize(msg: String): String = when {
        msg.contains("Geo-restricted") || msg.contains("geo") ->
            "Geo-restricted content — set Geo-bypass country in Settings"
        msg.contains("403") || msg.contains("forbidden") ->
            "Access denied (403) — try cookies from your browser"
        msg.contains("Private video") ->
            "Private video"
        msg.contains("Sign in") || msg.contains("age") || msg.contains("confirm") ->
            "Login required — set cookies-from-browser in Settings"
        msg.contains("format") ->
            "Requested format unavailable — try Best video + audio preset"
        msg.contains("throttl") ->
            "Rate-limited by site — try again later or use a proxy"
        msg.contains("429") ->
            "Too many requests (429) — wait or use a proxy"
        msg.contains("Network") ->
            "Network error — check internet connection"
        else -> msg
    }

    fun isTransient(humanizedMsg: String): Boolean =
        Regex("429|throttl|Network|timed out|Connection|Temporary", RegexOption.IGNORE_CASE)
            .containsMatchIn(humanizedMsg)
}
