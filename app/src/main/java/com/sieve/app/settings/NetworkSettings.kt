package com.sieve.app.settings

/**
 * Validation and normalisation for the Network settings rows (proxy, user-agent, speed limit). Pure,
 * so the rules are unit-tested; the Settings dialogs call [proxyError] etc. on every keystroke and only
 * enable Save while the text is acceptable. Blank always means "unset".
 */
object NetworkSettings {
    const val PROXY_HINT = "Use http://host:port or socks5://host:port"
    const val SPEED_HINT = "Use a number with K, M or G, e.g. 2M or 500K"
    const val MAX_USER_AGENT = 512

    /** scheme://[user[:pass]@]host[:port][/] — group 1 is the port when there is one. */
    private val PROXY = Regex(
        """^(?:https?|socks4a?|socks5h?)://(?:[^\s:@/]+(?::[^\s@/]*)?@)?(?:[A-Za-z0-9._-]+|\[[0-9A-Fa-f:.]+])(?::(\d{1,5}))?/?$""",
        RegexOption.IGNORE_CASE,
    )

    /** yt-dlp's `--limit-rate`: bytes per second, with an optional K / M / G suffix. */
    private val SPEED = Regex("""^(\d+(?:\.\d+)?)\s*([KMG])?$""", RegexOption.IGNORE_CASE)

    fun proxyError(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        val m = PROXY.matchEntire(v) ?: return PROXY_HINT
        val port = m.groupValues[1]
        if (port.isNotEmpty() && port.toInt() !in 1..65535) return "Port must be 1–65535"
        return null
    }

    fun userAgentError(raw: String): String? {
        if (raw.any { it.isISOControl() }) return "No line breaks or control characters"
        if (raw.trim().length > MAX_USER_AGENT) return "Too long (max $MAX_USER_AGENT characters)"
        return null
    }

    fun speedLimitError(raw: String): String? {
        val v = raw.trim()
        return if (v.isEmpty() || SPEED.matches(v)) null else SPEED_HINT
    }

    /** What gets stored/used: trimmed, or null when blank. */
    fun normalizeProxy(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    fun normalizeUserAgent(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** `"2 m"` -> `"2M"`; blank, `0` and `0K` mean unlimited -> null; anything unparseable -> null. */
    fun normalizeSpeedLimit(raw: String?): String? {
        val m = SPEED.matchEntire(raw?.trim().orEmpty()) ?: return null
        val number = m.groupValues[1]
        if (number.toDouble() == 0.0) return null
        return number + m.groupValues[2].uppercase()
    }
}
