package com.sieve.engine.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class YtdlpErrorsTest {

    private fun h(raw: String?) = YtdlpErrors.humanize(raw)

    // ---- one case per rule, in table order -------------------------------------------------

    private class Case(val raw: String, val kind: ErrorKind, val message: String, val transient: Boolean = false)

    private val cases = listOf(
        Case("instance not initialized", ErrorKind.OTHER, "Couldn't start the download engine"),
        Case("youtubedl-android failed to initialize", ErrorKind.OTHER, "Couldn't start the download engine"),
        Case(
            "java.io.IOException: Cannot run program \"/data/app/libpython.so\": error=13, Permission denied",
            ErrorKind.OTHER, "Couldn't start the download engine",
        ),
        Case("java.io.IOException: error=2, No such file or directory", ErrorKind.OTHER, "Couldn't start the download engine"),
        Case(
            "Process ID already exists", ErrorKind.OTHER, "Another link is still being read", transient = true,
        ),
        Case(
            "ERROR: unable to write data: [Errno 28] No space left on device",
            ErrorKind.DISK, "The disk is full",
        ),
        Case(
            "ERROR: unable to open for writing: [Errno 2] No such file or directory: '/data/x/Sign in.mp4'",
            ErrorKind.DISK, "Couldn't write to the output folder",
        ),
        Case(
            "ERROR: unable to open for writing: [Errno 13] Permission denied: '/data/x/video.mp4'",
            ErrorKind.DISK, "Permission denied writing to the output folder",
        ),
        Case(
            "ERROR: [vimeo] 76979871: The media is DRM protected",
            ErrorKind.DRM, "This media is DRM-protected and can't be downloaded",
        ),
        Case(
            "ERROR: [youtube] abc: Sign in to confirm you’re not a bot. Use --cookies-from-browser or --cookies",
            ErrorKind.LOGIN, "The site wants to confirm you're not a bot",
        ),
        Case(
            "ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate for some users.",
            ErrorKind.LOGIN, "Age-restricted — sign-in required",
        ),
        Case(
            "ERROR: [youtube] abc: Join this channel to get access to members-only content like this video",
            ErrorKind.LOGIN, "Members-only content",
        ),
        Case(
            "ERROR: [youtube] abc: Private video. Sign in if you've been granted access to this video",
            ErrorKind.PRIVATE, "This is private",
        ),
        Case("ERROR: [instagram] x: This account is private", ErrorKind.PRIVATE, "This is private"),
        Case(
            "ERROR: [vimeo] 1: This video is only available for registered users",
            ErrorKind.LOGIN, "This site needs you to be signed in",
        ),
        Case("ERROR: [instagram] x: empty media response", ErrorKind.LOGIN, "This site needs you to be signed in"),
        Case("ERROR: Unable to download JSON metadata: HTTP Error 401: Unauthorized", ErrorKind.LOGIN, "This site needs you to be signed in"),
        Case(
            "ERROR: [youtube] abc: The uploader has not made this video available in your country",
            ErrorKind.GEO, "Not available in your region",
        ),
        Case("ERROR: [x] y: This content is geo restricted", ErrorKind.GEO, "Not available in your region"),
        Case("ERROR: HTTP Error 451: Unavailable For Legal Reasons", ErrorKind.GEO, "Not available in your region"),
        Case(
            "ERROR: [x] y: Your IP address is blocked by the site",
            ErrorKind.BLOCKED, "The site is blocking requests from your network right now",
        ),
        Case(
            "ERROR: [youtube] abc: Video unavailable",
            ErrorKind.REMOVED, "Not available — it may have been removed or made private",
        ),
        Case(
            "ERROR: Unable to download JSON metadata: HTTP Error 404: Not Found",
            ErrorKind.REMOVED, "Not available — it may have been removed or made private",
        ),
        Case(
            "ERROR: [youtube] abc: Unable to download API page: HTTP Error 429: Too Many Requests",
            ErrorKind.RATE, "Rate-limited by the site", transient = true,
        ),
        Case("ERROR: [x] y: the site is throttling this client", ErrorKind.RATE, "Rate-limited by the site", transient = true),
        Case(
            "ERROR: unable to download video data: HTTP Error 403: Forbidden",
            ErrorKind.BLOCKED, "Access denied (403)", transient = true,
        ),
        Case("ERROR: Unsupported URL: https://example.com/x", ErrorKind.UNSUPPORTED, "This link isn't supported"),
        Case("ERROR: [generic] No suitable extractor found", ErrorKind.UNSUPPORTED, "This link isn't supported"),
        Case("ERROR: [twitter] 1: No video could be found in this tweet", ErrorKind.NOVIDEO, "No video found at this link"),
        Case(
            "ERROR: [youtube] abc: Requested format is not available. Use --list-formats for a list of available formats",
            ErrorKind.FORMAT, "The chosen quality isn't available for this video",
        ),
        Case(
            "ERROR: <urlopen error [Errno -3] Temporary failure in name resolution>",
            ErrorKind.NETWORK, "Network problem talking to the site", transient = true,
        ),
        Case(
            "ERROR: [youtube] abc: Unable to download webpage: The read operation timed out",
            ErrorKind.NETWORK, "Network problem talking to the site", transient = true,
        ),
    )

    @Test fun everyRuleMatches() {
        for (c in cases) {
            val got = h(c.raw)
            assertEquals(c.kind, got.kind, "kind for: ${c.raw}")
            assertEquals(c.message, got.message, "message for: ${c.raw}")
            assertEquals(c.transient, got.transient, "transient for: ${c.raw}")
        }
    }

    @Test fun androidHintsNeverPointAtMissingSettings() {
        val banned = listOf("firefox", "cookie", "proxy", "geo-bypass", "about", "engines", "onedrive")
        for (c in cases) {
            val s = YtdlpErrors.format(h(c.raw)).lowercase()
            for (b in banned) assertFalse(b in s, "'$b' in: $s")
        }
    }

    @Test fun androidHintWording() {
        assertEquals("Restart the app; if it keeps happening, reinstall Sieve.", h("instance not initialized").hint)
        assertEquals("Wait a moment and try again.", h("Process ID already exists").hint)
        assertNull(h("ERROR: No space left on device").hint)
        assertEquals("Pick a different folder in Settings → Storage.", h("ERROR: [Errno 2] No such file").hint)
        assertEquals("Pick a different folder in Settings → Storage.", h("ERROR: [Errno 13] Permission denied").hint)
        assertNull(h("ERROR: DRM protected").hint)
        val signIn = "Sieve for Android can't sign in to sites yet."
        assertEquals(signIn, h("ERROR: confirm you're not a bot").hint)
        assertEquals(signIn, h("ERROR: confirm your age").hint)
        assertEquals(signIn, h("ERROR: members-only").hint)
        assertEquals(signIn, h("ERROR: Sign in required").hint)
        assertEquals("Try again on a different network or VPN.", h("ERROR: geo restricted").hint)
        assertEquals("Try again later or switch network.", h("ERROR: IP address is blocked").hint)
        assertEquals("Wait a few minutes and retry.", h("ERROR: HTTP Error 429").hint)
        assertEquals("Retry, or update yt-dlp in Settings.", h("ERROR: HTTP Error 403").hint)
        assertEquals("Open the video itself and copy its address.", h("ERROR: Unsupported URL: x").hint)
        assertEquals("Pick \"Best video + audio\" or another preset.", h("ERROR: Requested format is not available").hint)
    }

    // ---- rule order -------------------------------------------------------------------------

    @Test fun diskErrorBeatsLoginWordsInTheTitle() =
        assertEquals(ErrorKind.DISK, h("ERROR: [Errno 2] No such file or directory: 'How to sign in.mp4'").kind)

    @Test fun ageBeatsGenericLogin() =
        assertEquals("Age-restricted — sign-in required", h("ERROR: Sign in to confirm your age").message)

    @Test fun privateBeatsGenericLogin() =
        assertEquals("This is private", h("ERROR: Private video. Sign in if you've been granted access").message)

    @Test fun ipBlockBeats403() =
        assertEquals(
            "The site is blocking requests from your network right now",
            h("ERROR: HTTP Error 403: Forbidden. Your IP address is blocked").message,
        )

    @Test fun rateBeatsNetworkOnDownloadWebpage() =
        assertEquals(ErrorKind.RATE, h("ERROR: Unable to download webpage: HTTP Error 429: Too Many Requests").kind)

    // ---- URLs must not trigger rules --------------------------------------------------------

    @Test fun urlContainingLoginDoesNotMatch() {
        val r = h("ERROR: Something odd happened at https://example.com/login/tips-video")
        assertEquals(ErrorKind.OTHER, r.kind)
        assertEquals("Something odd happened at https://example.com/login/tips-video", r.message)
    }

    @Test fun urlContainingRateLimitOrForbiddenDoesNotMatch() {
        for (u in listOf(
            "https://example.com/rate-limit-explained",
            "https://example.com/forbidden/page",
            "HTTP://EXAMPLE.COM/geo-restricted/429",
            "http://example.com/age-restricted/members-only/private-video",
            "https://example.com/watch?v=abc429&t=throttle",
        )) {
            val r = h("ERROR: Something odd happened at $u")
            assertEquals(ErrorKind.OTHER, r.kind, u)
        }
    }

    @Test fun urlIsBlankedButSurroundingTextStillMatches() =
        assertEquals(ErrorKind.UNSUPPORTED, h("ERROR: Unsupported URL: https://example.com/login/429").kind)

    @Test fun errorWordsOutsideUrlStillMatchWhenUrlPresent() =
        assertEquals(ErrorKind.RATE, h("ERROR: https://example.com/login -> HTTP Error 429: Too Many Requests").kind)

    // ---- WARNING lines are ignored when ERROR lines exist -----------------------------------

    @Test fun warningLineDoesNotTriggerRule() {
        val raw = "WARNING: [youtube] abc: Sign in to confirm your age. Some formats may be missing\n" +
            "ERROR: [youtube] abc: Unable to download webpage: HTTP Error 429: Too Many Requests"
        assertEquals(ErrorKind.RATE, h(raw).kind)
    }

    @Test fun warningMatchedNothingFromErrorFallsBackToErrorLine() {
        val raw = "WARNING: HTTP Error 403: Forbidden\nERROR: Something weird happened\n"
        val r = h(raw)
        assertEquals(ErrorKind.OTHER, r.kind)
        assertEquals("Something weird happened", r.message)
    }

    @Test fun outputWithoutErrorLinesIsScannedWhole() =
        // Engine-level exceptions carry no "ERROR:" prefix, so the whole text is matched.
        assertEquals(ErrorKind.NETWORK, h("WARNING: x\nThe read operation timed out").kind)

    @Test fun errorLinesKeepsOnlyErrorLines() {
        val raw = "[youtube] abc: Downloading\r\nWARNING: careful\r\nERROR: first\r\nERROR: [youtube] abc: second\r\n"
        assertEquals("ERROR: first\nERROR: [youtube] abc: second", YtdlpErrors.errorLines(raw))
    }

    @Test fun errorLinesBlankWhenNone() {
        assertEquals("", YtdlpErrors.errorLines("WARNING: x\nplain"))
        assertEquals("", YtdlpErrors.errorLines(null))
        assertEquals("", YtdlpErrors.errorLines(""))
    }

    // ---- fallback ---------------------------------------------------------------------------

    @Test fun fallbackUsesLastErrorLine() =
        assertEquals("second thing", h("ERROR: first thing\nERROR: [youtube] abc: second thing\nINFO tail").message)

    @Test fun fallbackStripsExtractorAndIdTag() =
        assertEquals("Something odd happened", h("ERROR: [youtube] ErAqN6gXqZQ: Something odd happened").message)

    @Test fun fallbackStripsBracketTagWithoutId() =
        assertEquals("Something odd", h("ERROR: [generic] Something odd").message)

    @Test fun fallbackStripsAnyPrefixBeforeError() =
        assertEquals("Something odd", h("yt-dlp: ERROR: Something odd").message)

    @Test fun fallbackStripsPleaseReportSuffix() {
        val raw = "ERROR: Something odd; please report this issue on https://github.com/yt-dlp/yt-dlp/issues?q= , " +
            "filling out the appropriate issue template. Confirm you are on the latest version using yt-dlp -U"
        assertEquals("Something odd", h(raw).message)
    }

    @Test fun fallbackWithoutErrorLineUsesLastNonBlankLine() {
        assertEquals("second line", h("Some unusual failure\nsecond line").message)
        assertEquals("second line", h("Some unusual failure\nsecond line\n\n").message)
    }

    @Test fun fallbackIsCappedAt200Chars() =
        assertEquals(200, h("ERROR: " + "x".repeat(300)).message.length)

    @Test fun emptyInputsGiveDownloadFailed() {
        for (raw in listOf(null, "", "   ", "\n\n", "ERROR:", "ERROR:   ")) {
            val r = h(raw)
            assertEquals(ErrorKind.OTHER, r.kind, "raw=$raw")
            assertEquals("Download failed", r.message, "raw=$raw")
        }
    }

    @Test fun fallbackHasNoHintAndIsNotTransient() {
        val r = h("ERROR: Something odd")
        assertNull(r.hint)
        assertFalse(r.transient)
    }

    // ---- format -----------------------------------------------------------------------------

    @Test fun formatJoinsMessageAndHintWithEmDash() {
        assertEquals(
            "Rate-limited by the site — Wait a few minutes and retry.",
            YtdlpErrors.format(h("ERROR: HTTP Error 429: Too Many Requests")),
        )
    }

    @Test fun formatIsJustTheMessageWithoutAHint() {
        assertEquals("The disk is full", YtdlpErrors.format(h("ERROR: [Errno 28] No space left on device")))
        assertEquals("Download failed", YtdlpErrors.format(h(null)))
        assertEquals("m", YtdlpErrors.format(HumanError(ErrorKind.OTHER, "m", "", false)))
    }
}
