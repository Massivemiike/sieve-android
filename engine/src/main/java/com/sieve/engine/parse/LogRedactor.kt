package com.sieve.engine.parse

/**
 * Keeps credentials out of everything that gets logged. logcat survives into bug reports, and a failed
 * run's text also lands in the queue row's log, so neither may carry a proxy password, an auth header or
 * a login. The cookies PATH is harmless and is left alone; cookie CONTENTS never reach a log line at all.
 *
 * [redactArgs] is for a yt-dlp argument vector, [redact] for free text (yt-dlp's output, exception messages).
 */
object LogRedactor {
    private const val MASK = "***"

    /** `scheme://user:pass@host` and `scheme://user@host`: the userinfo is replaced, the host stays for debugging. */
    private val USERINFO = Regex("([A-Za-z][A-Za-z0-9+.-]*://)[^/@\\s]+@")

    /**
     * `Authorization: ...`, `Cookie: ...` and friends, to the end of the line. Only at the start of a line, where a
     * header dump puts them: a video title like "Cookie: the movie" inside a `Destination:` line is not one.
     */
    private val SECRET_HEADER = Regex(
        "^(\\s*(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key))(\\s*[:=]\\s*)[^\\r\\n]+",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    /** yt-dlp flags whose whole value is a secret. */
    private val SECRET_FLAGS = setOf(
        "--username", "-u", "--password", "-p", "--twofactor", "-2", "--video-password",
        "--ap-username", "--ap-password", "--client-certificate-password",
    )

    private val HEADER_FLAGS = setOf("--add-header", "--add-headers")

    fun redact(text: String): String =
        text.replace(USERINFO, "$1$MASK@").replace(SECRET_HEADER) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }

    fun redactArgs(args: List<String>): List<String> {
        val out = ArrayList<String>(args.size)
        var previous: String? = null
        for (arg in args) {
            out += when {
                previous in SECRET_FLAGS -> MASK
                previous in HEADER_FLAGS -> maskHeader(arg)
                else -> redactFlagWithValue(arg)
            }
            previous = arg
        }
        return out
    }

    /** `--flag=value` spelling of the same flags; anything else is just scanned for URL userinfo. */
    private fun redactFlagWithValue(arg: String): String {
        if (arg.startsWith("--")) {
            val eq = arg.indexOf('=')
            if (eq > 0) {
                val flag = arg.substring(0, eq)
                val value = arg.substring(eq + 1)
                if (flag in SECRET_FLAGS) return "$flag=$MASK"
                if (flag in HEADER_FLAGS) return "$flag=${maskHeader(value)}"
            }
        }
        return redact(arg)
    }

    /** `Name:value` keeps the (harmless) name so the log still says which header was sent. */
    private fun maskHeader(header: String): String {
        val colon = header.indexOf(':')
        return if (colon < 0) MASK else header.substring(0, colon + 1) + MASK
    }
}
