package com.sieve.engine.site

import java.net.URLDecoder

/**
 * Pure, JVM-only site rules ported from the desktop app's `normalizeUrl` / `fallbackUrl` /
 * `errorText` (electron/main.ts). Everything is string-level on purpose: rebuilding a URL via
 * `java.net.URI` re-encodes `%` sequences and would change what yt-dlp is handed.
 */
object SiteRules {

    /** scheme://, authority, path, ?query, #fragment — each part kept verbatim. */
    private val URL_PARTS = Regex("""^(https?://)([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$""", RegexOption.IGNORE_CASE)
    private val LINKEDIN_HOST = Regex("""(^|\.)linkedin\.com$""")
    private val LINKEDIN_COUNTRY_HOST = Regex("""^[a-z]{2}\.linkedin\.com$""")
    private val FACEBOOK_HOST = Regex("""(^|\.)facebook\.com$""")
    private val HTTP_URL = Regex("""^https?://[^/\s]+\S*$""", RegexOption.IGNORE_CASE)

    private const val LINKEDIN_WWW = "www.linkedin.com"
    private const val LINKEDIN_EMBED_PREFIX = "/embed/feed/update/"
    private const val FACEBOOK_PLAYER_PATH = "/plugins/video.php"

    /** Port of the desktop's LOGIN_REQUIRED_RE: failures a cookies file could fix (sign-in, age gate, members-only...). */
    private val LOGIN_REQUIRED = Regex(
        """\bsign in\b|\blog ?in\b|\blogged[- ]in\b|registered users|confirm your age|confirm you.?re not a bot|""" +
            """age[- ]restrict|private (?:video|account|post)|members[- ]only|empty media response|use --cookies|HTTP Error 401""",
        RegexOption.IGNORE_CASE,
    )

    private val VIMEO_WEB_CLIENT_ERROR = Regex("""web client only works when logged-in""", RegexOption.IGNORE_CASE)

    // The id is the LAST numeric path segment (album/showcase URLs carry another id first);
    // unlisted links append a 10-hex hash. The last group is "a ?/# or the end of the string".
    private val VIMEO_ID = Regex(
        """vimeo\.com/(?:[^?#]*?/)??(\d{5,})(?:/([0-9a-f]{10}))?/?(?:[?#]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val PLAYER_VIMEO = Regex("""player\.vimeo\.com""", RegexOption.IGNORE_CASE)
    private val URL_IN_TEXT = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /** URL forms yt-dlp handles better than the ones people paste. Anything else comes back trimmed. */
    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        val m = URL_PARTS.matchEntire(trimmed) ?: return trimmed
        val scheme = m.groupValues[1]
        val authority = m.groupValues[2]
        val path = m.groupValues[3]
        val query = m.groupValues[4]
        val fragment = m.groupValues[5]

        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at + 1) else ""
        val hostPort = authority.substring(at + 1)
        val colon = hostPort.lastIndexOf(':')
        val hasPort = colon >= 0 && hostPort.length > colon + 1 && hostPort.substring(colon + 1).all { it.isDigit() }
        val host = (if (hasPort) hostPort.substring(0, colon) else hostPort)
        val port = if (hasPort) hostPort.substring(colon) else ""
        val lowerHost = host.lowercase()

        if (LINKEDIN_HOST.containsMatchIn(lowerHost)) {
            // Country sub-domains (uk., ca., in., ...) and /embed/ post URLs fall through to the
            // generic extractor; the www /feed/update/ form works.
            val newHost = if (LINKEDIN_COUNTRY_HOST.matches(lowerHost)) LINKEDIN_WWW else host
            val newPath = if (path.startsWith(LINKEDIN_EMBED_PREFIX)) path.removePrefix("/embed") else path
            return scheme + userInfo + newHost + port + newPath + query + fragment
        }

        if (FACEBOOK_HOST.containsMatchIn(lowerHost) && path.startsWith(FACEBOOK_PLAYER_PATH)) {
            // Facebook embed player: hand yt-dlp the video URL inside href=.
            val href = queryValue(query, "href")
            if (href != null && HTTP_URL.matches(href)) return href
        }
        return trimmed
    }

    /** True when a failure's yt-dlp `ERROR:` text says the site wants a login, so a cookies file is worth trying. */
    fun looksLoginRequired(failure: String?): Boolean = LOGIN_REQUIRED.containsMatchIn(errorText(failure))

    /**
     * A second URL to try when a site rejects the first form. Vimeo login-walls its page URLs
     * ("The web client only works when logged-in") but the player URL for the same public or
     * unlisted video still works anonymously.
     */
    fun fallbackUrl(url: String, err: String): String? {
        if (!VIMEO_WEB_CLIENT_ERROR.containsMatchIn(err)) return null
        if (PLAYER_VIMEO.containsMatchIn(url)) return null
        val m = VIMEO_ID.find(url) ?: return null
        val id = m.groupValues[1]
        val hash = m.groupValues[2]
        return "https://player.vimeo.com/video/$id" + if (hash.isNotEmpty()) "?h=$hash" else ""
    }

    /**
     * Only yt-dlp's `ERROR:` lines, minus URLs. Titles/paths in other lines (and URLs inside the
     * error itself) caused false matches against the login/age/429 patterns.
     */
    fun errorText(stderr: String?): String =
        stderr.orEmpty().lines()
            .filter { it.contains("ERROR:") }
            .joinToString("\n")
            .replace(URL_IN_TEXT, " ")

    /** First `name=` value in a `?a=b&c=d` query, URL-decoded; null when absent or undecodable. */
    private fun queryValue(query: String, name: String): String? {
        if (query.length <= 1) return null
        for (pair in query.substring(1).split('&')) {
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            if (key != name) continue
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            return try {
                URLDecoder.decode(value, "UTF-8").trim().takeIf { it.isNotEmpty() }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return null
    }
}
