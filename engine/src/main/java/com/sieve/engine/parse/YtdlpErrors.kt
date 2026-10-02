package com.sieve.engine.parse

/** What went wrong, coarsely. Mirrors the desktop `ErrorKind` minus `cookies` (no browser cookies on Android). */
enum class ErrorKind {
    DRM, LOGIN, PRIVATE, REMOVED, GEO, BLOCKED, RATE, UNSUPPORTED, NOVIDEO, FORMAT, DISK, NETWORK,
    /** Android 17 only: the link or proxy is on the local network and the app has not been given that access. */
    LOCAL_NETWORK,
    OTHER,
}

/** A yt-dlp failure a person can act on. [transient] = worth one automatic retry. */
data class HumanError(
    val kind: ErrorKind,
    val message: String,
    val hint: String? = null,
    val transient: Boolean = false,
)

/**
 * Turns yt-dlp error output into a [HumanError]. Port of the desktop `ytdlpErrors.ts` rule table:
 * patterns are anchored on yt-dlp's own phrasing and checked IN ORDER (earlier rules are more
 * specific). Loose substring tests mislabelled errors before ("webpage" contains "age" -> "Login
 * required"), so:
 *  - URLs are blanked before matching (they are echoed in many errors and can hold any word, e.g.
 *    `.../login-tips-video` or a video id containing `429`);
 *  - when the output has `ERROR:` lines only those are matched — a WARNING line must never decide
 *    the verdict (downloads pass `--no-warnings` as on desktop, but analyze keeps its warnings).
 *
 * Android differs from desktop in the hints only: they point at the settings Android has —
 * Settings → Network → Cookies file (a cookies.txt import; there are no browser cookies) and the
 * Proxy row (there is no geo-bypass country). The desktop browser-cookies rule is dropped, and the
 * engine-start rule matches the youtubedl-android library's failure phrasing instead of Node's
 * `spawn ENOENT`.
 */
object YtdlpErrors {

    private const val SIGN_IN_HINT = "Import a cookies.txt from a signed-in browser under Settings → Network → Cookies file."
    private const val STORAGE_HINT = "Pick a different folder in Settings → Storage."

    /**
     * The wording of the failure the queue writes when a download of a LAN link (or through a LAN proxy) fails while Android 17's
     * local-network permission is missing (see [com.sieve.engine.site.LocalNetwork]): the OS drops those connections, yt-dlp only
     * sees a timeout, and the user has to be told the real cause. [localNetworkBlocked] builds the text, the first rule below reads it.
     */
    private const val LOCAL_NETWORK_BLOCKED = "Sieve is not allowed to reach devices on your local network"
    private const val LOCAL_NETWORK_HINT = "Allow Nearby devices for Sieve in Settings → Apps → Sieve → Permissions, then retry."

    /** The raw error line for a download that Android blocked: ERROR-prefixed like yt-dlp's own lines, with the host for the log. */
    fun localNetworkBlocked(host: String?): String =
        "ERROR: $LOCAL_NETWORK_BLOCKED" + host?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()

    private val IC = RegexOption.IGNORE_CASE
    private val URL_RE = Regex("https?://\\S+", IC)
    private val LINE_BREAK = Regex("\\r?\\n")

    private class Rule(val re: Regex, val error: HumanError)

    private fun rule(pattern: String, error: HumanError) = Rule(Regex(pattern, IC), error)

    private val RULES: List<Rule> = listOf(
        // Our own text, written by the queue (not yt-dlp's), so it goes first: whatever else the log says, this is the cause.
        rule(
            LOCAL_NETWORK_BLOCKED,
            HumanError(ErrorKind.LOCAL_NETWORK, "Sieve can't reach your local network", LOCAL_NETWORK_HINT),
        ),
        rule(
            "instance not initialized|failed to initialize|cannot run program|error=(?:2|13)\\b",
            HumanError(
                ErrorKind.OTHER, "Couldn't start the download engine",
                "Restart the app; if it keeps happening, reinstall Sieve.",
            ),
        ),
        rule(
            "process id already exists",
            HumanError(ErrorKind.OTHER, "Another link is still being read", "Wait a moment and try again.", transient = true),
        ),
        // Disk errors before site rules: their "[Errno N] ... '<path>'" text can contain a title with
        // words like "sign in".
        rule("errno 28|no space left", HumanError(ErrorKind.DISK, "The disk is full")),
        rule(
            "errno 2\\b|ENOENT|no such file or directory",
            HumanError(ErrorKind.DISK, "Couldn't write to the output folder", STORAGE_HINT),
        ),
        rule(
            "errno 13|EACCES|permission denied",
            HumanError(ErrorKind.DISK, "Permission denied writing to the output folder", STORAGE_HINT),
        ),
        rule("DRM protected", HumanError(ErrorKind.DRM, "This media is DRM-protected and can't be downloaded")),
        rule(
            "confirm you.?re not a bot",
            HumanError(ErrorKind.LOGIN, "The site wants to confirm you're not a bot", SIGN_IN_HINT),
        ),
        rule(
            "confirm your age|age[- ]restrict|inappropriate for some users",
            HumanError(ErrorKind.LOGIN, "Age-restricted — sign-in required", SIGN_IN_HINT),
        ),
        rule("members[- ]only|join this channel", HumanError(ErrorKind.LOGIN, "Members-only content", SIGN_IN_HINT)),
        rule(
            "private video|this (?:video|account|post) is private|private account",
            HumanError(ErrorKind.PRIVATE, "This is private"),
        ),
        rule(
            "only available for registered users|\\blogged[- ]in\\b|\\blog ?in\\b|\\bsign in\\b|use --cookies|" +
                "empty media response|requires? authentication|HTTP Error 401",
            HumanError(ErrorKind.LOGIN, "This site needs you to be signed in", SIGN_IN_HINT),
        ),
        rule(
            "(?:available|blocked(?: it)?) in your (?:country|region)|\\bin your (?:country|region)\\b|" +
                "geo[- ]?restrict|not available from your location|HTTP Error 451",
            HumanError(ErrorKind.GEO, "Not available in your region", "Set a proxy under Settings → Network, or try another network."),
        ),
        rule(
            "IP address is blocked",
            HumanError(
                ErrorKind.BLOCKED, "The site is blocking requests from your network right now",
                "Try again later, or set a proxy under Settings → Network.",
            ),
        ),
        rule(
            "video unavailable|has been removed|no longer available|does not exist|this video is not available|" +
                "HTTP Error (?:400|404|410)|cannot parse data",
            HumanError(ErrorKind.REMOVED, "Not available — it may have been removed or made private"),
        ),
        rule(
            "HTTP Error 429|too many requests|rate[- ]limit|throttl",
            HumanError(ErrorKind.RATE, "Rate-limited by the site", "Wait a few minutes and retry.", transient = true),
        ),
        rule(
            "HTTP Error 403|\\bforbidden\\b",
            HumanError(ErrorKind.BLOCKED, "Access denied (403)", "Retry, or update yt-dlp in Settings.", transient = true),
        ),
        rule(
            "unsupported URL|no suitable extractor|is not a valid URL",
            HumanError(ErrorKind.UNSUPPORTED, "This link isn't supported", "Open the video itself and copy its address."),
        ),
        rule(
            "no video could be found|no downloadable videos|unable to extract video|there'?s no video",
            HumanError(ErrorKind.NOVIDEO, "No video found at this link"),
        ),
        rule(
            "requested format is not available",
            HumanError(
                ErrorKind.FORMAT, "The chosen quality isn't available for this video",
                "Pick \"Best video + audio\" or another preset.",
            ),
        ),
        rule(
            "timed out|connection (?:reset|refused|aborted)|temporary failure|getaddrinfo|network is unreachable|" +
                "unable to download (?:webpage|JSON)",
            HumanError(ErrorKind.NETWORK, "Network problem talking to the site", transient = true),
        ),
    )

    /** Only the lines that contain `ERROR:`, joined by `\n`; blank when there are none. */
    fun errorLines(raw: String?): String =
        raw.orEmpty().split(LINE_BREAK).filter { it.contains("ERROR:") }.joinToString("\n")

    fun humanize(raw: String?): HumanError {
        val text = raw.orEmpty()
        val errors = errorLines(text)
        // ERROR lines decide when present (a WARNING line must not trigger a rule); engine-level
        // failures that carry no `ERROR:` prefix are matched against the whole text.
        val scan = (errors.ifEmpty { text }).replace(URL_RE, " ")
        for (r in RULES) if (r.re.containsMatchIn(scan)) return r.error

        val lines = text.split(LINE_BREAK)
        // Last ERROR line, else the last non-blank line (desktop takes the raw last line, which is
        // empty for output ending in a newline).
        val line = lines.lastOrNull { it.contains("ERROR:") } ?: lines.lastOrNull { it.isNotBlank() }.orEmpty()
        val message = line
            .replace(Regex("^.*?ERROR:\\s*"), "")
            .replace(Regex("^\\[[^\\]]+\\]\\s*(?:[\\w-]+:\\s*)?"), "")
            .replace(Regex(";?\\s*please report this issue.*$", IC), "")
            .trim()
            .take(200)
        return HumanError(ErrorKind.OTHER, message.ifEmpty { "Download failed" })
    }

    /** `message`, plus " — hint" when there is one. */
    fun format(h: HumanError): String = if (h.hint.isNullOrEmpty()) h.message else "${h.message} — ${h.hint}"
}
