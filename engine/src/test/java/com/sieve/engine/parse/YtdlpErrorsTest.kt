package com.sieve.engine.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtdlpErrorsTest {

    private fun h(raw: String?) = YtdlpErrors.humanize(raw)

    private companion object {
        const val SOUNDCLOUD_BLOCKED = "SoundCloud is temporarily blocking requests"
        const val BUG_REPORT_TAIL = "; please report this issue on  https://github.com/yt-dlp/yt-dlp/issues?q= , " +
            "filling out the appropriate issue template. Confirm you are on the latest version using  yt-dlp -U"

        // The three ways yt-dlp's soundcloud extractor fails while CloudFront's WAF answers every request with an empty 202.
        // Worded exactly as yt-dlp prints them (run through YoutubeDL with the extractor's downloads stubbed out).
        /** No cached client id: the main page has no scripts, so none can be read. What the owner's phone showed. */
        const val SOUNDCLOUD_NO_CLIENT_ID = "ERROR: [soundcloud] Unable to extract client id$BUG_REPORT_TAIL"
        /** A cached client id, and the API says 403 (the extractor refreshes the id once, which fails the same way, then raises). */
        const val SOUNDCLOUD_API_403 =
            "ERROR: [soundcloud] Unable to download JSON metadata: HTTP Error 403: Forbidden (caused by <HTTPError 403: Forbidden>)$BUG_REPORT_TAIL"
        /** A cached client id, and the API answers with an empty body instead of JSON. */
        const val SOUNDCLOUD_API_NOT_JSON =
            "ERROR: [soundcloud] ethmusic/lostin-powers-she-so-heavy: Failed to parse JSON " +
                "(caused by JSONDecodeError(\"Expecting value in '': line 1 column 1 (char 0)\"))$BUG_REPORT_TAIL"
    }

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
        Case(SOUNDCLOUD_NO_CLIENT_ID, ErrorKind.BLOCKED, SOUNDCLOUD_BLOCKED, transient = true),
        Case("ERROR: Unable to extract client id", ErrorKind.BLOCKED, SOUNDCLOUD_BLOCKED, transient = true),
        Case(SOUNDCLOUD_API_403, ErrorKind.BLOCKED, SOUNDCLOUD_BLOCKED, transient = true),
        Case(SOUNDCLOUD_API_NOT_JSON, ErrorKind.BLOCKED, SOUNDCLOUD_BLOCKED, transient = true),
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

    // Cookies.txt and the proxy exist on Android now (Settings → Network); browser cookies, a geo-bypass
    // country, the About screen and OneDrive folders do not.
    @Test fun androidHintsNeverPointAtMissingSettings() {
        val banned = listOf("firefox", "geo-bypass", "about", "engines", "onedrive")
        for (c in cases) {
            val s = YtdlpErrors.format(h(c.raw)).lowercase()
            for (b in banned) assertFalse(b in s, "'$b' in: $s")
        }
    }

    @Test fun loginAndBlockHintsPointAtTheNetworkSettings() {
        val loginHints = listOf(
            "ERROR: confirm you're not a bot", "ERROR: confirm your age", "ERROR: members-only", "ERROR: Sign in required",
        ).map { h(it).hint.orEmpty() }
        for (hint in loginHints) {
            assertTrue("cookies.txt" in hint && "Settings → Network → Cookies file" in hint, hint)
        }
        for (raw in listOf("ERROR: geo restricted", "ERROR: IP address is blocked")) {
            assertTrue("proxy under Settings → Network" in h(raw).hint.orEmpty(), raw)
        }
    }

    @Test fun androidHintWording() {
        assertEquals("Restart the app; if it keeps happening, reinstall Sieve.", h("instance not initialized").hint)
        assertEquals("Wait a moment and try again.", h("Process ID already exists").hint)
        assertNull(h("ERROR: No space left on device").hint)
        assertEquals("Pick a different folder in Settings → Storage.", h("ERROR: [Errno 2] No such file").hint)
        assertEquals("Pick a different folder in Settings → Storage.", h("ERROR: [Errno 13] Permission denied").hint)
        assertNull(h("ERROR: DRM protected").hint)
        val signIn = "Import a cookies.txt from a signed-in browser under Settings → Network → Cookies file."
        assertEquals(signIn, h("ERROR: confirm you're not a bot").hint)
        assertEquals(signIn, h("ERROR: confirm your age").hint)
        assertEquals(signIn, h("ERROR: members-only").hint)
        assertEquals(signIn, h("ERROR: Sign in required").hint)
        assertEquals("Set a proxy under Settings → Network, or try another network.", h("ERROR: geo restricted").hint)
        assertEquals("Try again later, or set a proxy under Settings → Network.", h("ERROR: IP address is blocked").hint)
        assertEquals("Wait a few minutes and retry.", h("ERROR: HTTP Error 429").hint)
        assertEquals("Retry, or update yt-dlp in Settings.", h("ERROR: HTTP Error 403").hint)
        assertEquals("Open the video itself and copy its address.", h("ERROR: Unsupported URL: x").hint)
        assertEquals("Pick \"Best video + audio\" or another preset.", h("ERROR: Requested format is not available").hint)
    }

    // ---- SoundCloud's WAF block ---------------------------------------------------------------

    @Test fun soundcloudWafBlockSaysSoAndWhenToTryAgain() {
        for (raw in listOf(SOUNDCLOUD_NO_CLIENT_ID, SOUNDCLOUD_API_403, SOUNDCLOUD_API_NOT_JSON)) {
            val r = h(raw)
            assertEquals(SOUNDCLOUD_BLOCKED, r.message, raw)
            assertEquals("Try again in a few minutes.", r.hint, raw)
            assertEquals(ErrorKind.BLOCKED, r.kind, raw)
            assertTrue(r.transient, "it passes by itself, so it is worth the one automatic retry: $raw")
            assertEquals("SoundCloud is temporarily blocking requests — Try again in a few minutes.", YtdlpErrors.format(r), raw)
        }
    }

    @Test fun soundcloudBlockStillWinsWhenTheLogHasWarningsAround() {
        // Analyze keeps its warnings; the non-fatal "Downloading JS asset" failures come first and are not the verdict.
        val raw = "[soundcloud] None: Downloading main page\n" +
            "WARNING: [soundcloud] None: Unable to download webpage: HTTP Error 404: Not Found\n" +
            SOUNDCLOUD_NO_CLIENT_ID + "\n"
        assertEquals(SOUNDCLOUD_BLOCKED, h(raw).message)
    }

    @Test fun everySoundcloudExtractorCountsNotJustTracks() {
        for (ie in listOf("soundcloud:set", "soundcloud:user", "soundcloud:playlist")) {
            assertEquals(
                SOUNDCLOUD_BLOCKED,
                h("ERROR: [$ie] artist/sets/mix: Unable to download JSON metadata: HTTP Error 403: Forbidden (caused by <HTTPError 403: Forbidden>)").message,
                ie,
            )
        }
    }

    @Test fun otherSitesKeepTheirOwnVerdictForTheSameWording() {
        // A 403 anywhere else is still the generic "Access denied"; "Failed to parse JSON" elsewhere is not a block.
        assertEquals(
            "Access denied (403)",
            h("ERROR: [vimeo] 76979871: Unable to download JSON metadata: HTTP Error 403: Forbidden (caused by <HTTPError 403: Forbidden>)").message,
        )
        val json = h("ERROR: [youtube] abc: Failed to parse JSON (caused by JSONDecodeError(\"Expecting value\"))")
        assertEquals(ErrorKind.OTHER, json.kind)
        assertFalse(json.transient)
        // A SoundCloud link inside another site's error is a URL, and URLs never decide.
        assertEquals(
            "Access denied (403)",
            h("ERROR: [generic] Unable to download webpage: HTTP Error 403: Forbidden (https://soundcloud.com/some/track)").message,
        )
    }

    @Test fun soundcloudErrorsThatAreNotTheBlockKeepTheirOwnRule() {
        assertEquals(
            ErrorKind.REMOVED,
            h("ERROR: [soundcloud] artist/gone: Unable to download JSON metadata: HTTP Error 404: Not Found (caused by <HTTPError 404: Not Found>)").kind,
        )
        assertEquals(
            ErrorKind.RATE,
            h("ERROR: [soundcloud] 123: Unable to download JSON metadata: HTTP Error 429: Too Many Requests (caused by <HTTPError 429: Too Many Requests>)").kind,
        )
        assertEquals(ErrorKind.GEO, h("ERROR: [soundcloud] 123: This track is not available in your country").kind)
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
