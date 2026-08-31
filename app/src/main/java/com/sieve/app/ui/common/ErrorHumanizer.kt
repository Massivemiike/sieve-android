package com.sieve.app.ui.common

/**
 * Turns a raw yt-dlp/ffmpeg error into a short, actionable message. Ordered rules ported from the
 * desktop App.tsx humanizer (rate-limit / geo / login / format / 403 / throttle / network).
 */
object ErrorHumanizer {

    fun humanize(raw: String): String {
        val s = raw.trim()
        val l = s.lowercase()
        return when {
            "429" in l || "too many requests" in l || ("rate" in l && "limit" in l) ->
                "Rate-limited by the site — wait a moment and retry."
            "geo" in l || "not available in your country" in l || "in your region" in l ->
                "Geo-restricted — set a geo-bypass country in Settings."
            "sign in" in l || "confirm your age" in l || "age-restricted" in l ||
                "private video" in l || "members-only" in l || "login" in l ->
                "Login required — add a cookies.txt file in Settings."
            "requested format" in l || "format is not available" in l ->
                "That format isn't available — try a different preset."
            "403" in l || "forbidden" in l ->
                "Access denied (HTTP 403) — the site refused the request."
            "throttl" in l ->
                "The site is throttling downloads — retrying may help."
            "timed out" in l || "timeout" in l || "connection" in l ||
                "network" in l || "unable to download" in l ->
                "Network problem — check your connection and retry."
            s.isEmpty() -> "Download failed."
            else -> s.lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: "Download failed."
        }
    }
}
