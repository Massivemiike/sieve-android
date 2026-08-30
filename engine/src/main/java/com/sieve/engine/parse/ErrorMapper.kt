package com.sieve.engine.parse

/**
 * Ports the desktop error extraction + humanization + transient classification.
 * Preserved quirks: `age` matches inside words like "message"; humanize erases
 * its own `throttl` transient signal (so throttled errors are NOT auto-retried,
 * but 429/Network still are). Always humanize THEN isTransient(humanized).
 */
object ErrorMapper {
    fun extract(logs: List<String>, exitCode: Int): String {
        logs.lastOrNull { it.contains("ERROR:") }?.let { return it.trim() }
        logs.lastOrNull { it.isNotBlank() }?.let { return it.trim() }
        return "yt-dlp exited with code $exitCode"
    }

    fun humanize(msg: String): String = when {
        Regex("geo|not available in your (country|location)", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
            "Not available in your region — try a proxy or geo-bypass"
        msg.contains("403") ->
            "Access denied (HTTP 403) — the site blocked the request; try cookies or a proxy"
        Regex("private", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
            "This video is private"
        Regex("sign in|age|confirm your age", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
            "Login required — set cookies-from-browser in Settings"
        Regex("requested format is not available|no video formats", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
            "Requested format not available — pick a different quality"
        Regex("throttl", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
            "Rate-limited by site — try again later or use a proxy"
        msg.contains("429") ->
            "Too many requests (429) — wait and retry"
        Regex("network|unreachable|timed out|connection", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
            "Network error — check your connection and retry"
        else -> msg
    }

    fun isTransient(humanizedMsg: String): Boolean =
        Regex("429|throttl|Network|timed out|Connection|Temporary", RegexOption.IGNORE_CASE)
            .containsMatchIn(humanizedMsg)
}
