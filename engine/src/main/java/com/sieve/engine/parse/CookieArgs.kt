package com.sieve.engine.parse

/** Cookie flag helpers for the cookie-format retry path. */
object CookieArgs {
    private val cookieFlags = setOf("--cookies-from-browser", "--cookies")

    /** Removes cookie flags AND their value token. */
    fun strip(args: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            if (args[i] in cookieFlags) i += 2 else { out += args[i]; i++ }
        }
        return out
    }

    fun usesCookies(args: List<String>): Boolean = args.any { it in cookieFlags }
}
