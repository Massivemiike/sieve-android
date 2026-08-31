package com.sieve.queue.core

enum class RetryClass { TRANSIENT, PERMANENT }

data class RetryPolicy(val maxAutoRetries: Int = 1, val backoffMs: Long = 5_000L)

/**
 * Decides whether a [FailureInfo] should auto-retry. Ports the desktop transient regex plus 5xx
 * and fragment failures, with an explicit permanent-error list checked FIRST.
 *
 * `\bage\b` is word-boundaried on purpose: a bare `age` token matches "webpage"/"message" and would
 * misclassify transient failures (e.g. "Unable to download webpage: throttled") as permanent.
 * Unknown failures default to PERMANENT — never auto-retry something we don't recognize.
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

    fun classify(info: FailureInfo): RetryClass {
        val haystack = buildString {
            append(info.message)
            info.stderrTail?.let { append('\n').append(it) }
        }
        if (PERMANENT.containsMatchIn(haystack)) return RetryClass.PERMANENT
        if (TRANSIENT.containsMatchIn(haystack)) return RetryClass.TRANSIENT
        return RetryClass.PERMANENT // default: don't retry unknown failures
    }
}
