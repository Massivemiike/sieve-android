package com.sieve.queue.core

import com.sieve.engine.parse.YtdlpErrors

enum class RetryClass { TRANSIENT, PERMANENT }

data class RetryPolicy(val maxAutoRetries: Int = 1, val backoffMs: Long = 5_000L)

/**
 * Decides whether a [FailureInfo] should auto-retry.
 *
 * Downloads are decided exactly as on desktop (`human.transient` in App.tsx): by the same yt-dlp rule
 * table that words the message the user reads ([YtdlpErrors], in rule order — rate limit, 403 and network
 * failures are transient, private/login/geo/removed are not). It matches yt-dlp's ERROR lines only (a
 * WARNING line never decides) with URLs blanked, so a slug like `/login/` or `/403-error` can't either.
 * Everything else is PERMANENT: never auto-retry something we don't recognize.
 *
 * Transcodes keep the substring verdict below: ffmpeg's stderr has no `ERROR:` lines or yt-dlp phrasing.
 * `\bage\b` is word-boundaried on purpose: a bare `age` token matches "webpage"/"message".
 */
object RetryClassifier {

    private val TRANSIENT = Regex(
        "429|throttl|network|timed?\\s*out|connection|temporar|reset by peer|HTTP Error 5\\d\\d|unreachable|fragment",
        RegexOption.IGNORE_CASE,
    )

    private val PERMANENT = Regex(
        "403|404|private|not available|Requested format is not available|your country|geo|" +
            "Sign in|login|\\bage\\b|Unsupported|Unknown encoder|unsupported codec|Invalid argument",
        RegexOption.IGNORE_CASE,
    )

    private val URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    fun classify(info: FailureInfo, kind: JobKind): RetryClass = when (kind) {
        JobKind.DOWNLOAD -> classifyDownload(info)
        JobKind.TRANSCODE -> classifyTranscode(info)
    }

    /**
     * `message` is already the ERROR lines (cut to 500 chars) and `stderrTail` the whole blob; the rule table
     * keeps only the ERROR lines of both, so a long first line can't hide the line that carries the verdict.
     */
    private fun classifyDownload(info: FailureInfo): RetryClass {
        val text = listOfNotNull(info.message, info.stderrTail).joinToString("\n")
        return if (YtdlpErrors.humanize(text).transient) RetryClass.TRANSIENT else RetryClass.PERMANENT
    }

    private fun classifyTranscode(info: FailureInfo): RetryClass {
        val haystack = buildString {
            append(info.message)
            info.stderrTail?.let { append('\n').append(it) }
        }.replace(URL, " ")
        if (PERMANENT.containsMatchIn(haystack)) return RetryClass.PERMANENT
        if (TRANSIENT.containsMatchIn(haystack)) return RetryClass.TRANSIENT
        return RetryClass.PERMANENT // default: don't retry unknown failures
    }
}
