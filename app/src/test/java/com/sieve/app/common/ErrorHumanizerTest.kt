package com.sieve.app.common

import com.sieve.app.ui.common.ErrorHumanizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The adapter only formats "message — hint"; the rule table itself is tested in :engine (YtdlpErrorsTest). */
class ErrorHumanizerTest {

    @Test fun rateLimit() =
        assertEquals(
            "Rate-limited by the site — Wait a few minutes and retry.",
            ErrorHumanizer.humanize("ERROR: HTTP Error 429: Too Many Requests"),
        )

    @Test fun geo() =
        assertEquals(
            "Not available in your region — Try again on a different network or VPN.",
            ErrorHumanizer.humanize("ERROR: This video is geo restricted in your region"),
        )

    @Test fun loginHintDoesNotMentionCookies() {
        val s = ErrorHumanizer.humanize("ERROR: Sign in to confirm your age")
        assertTrue(s.startsWith("Age-restricted"), s)
        assertTrue(s.endsWith("Sieve for Android can't sign in to sites yet."), s)
        assertFalse(s.contains("cookie", ignoreCase = true), s)
        assertFalse(s.contains("firefox", ignoreCase = true), s)
    }

    @Test fun format() =
        assertEquals(
            "The chosen quality isn't available for this video — Pick \"Best video + audio\" or another preset.",
            ErrorHumanizer.humanize("ERROR: Requested format is not available"),
        )

    @Test fun forbidden() =
        assertEquals(
            "Access denied (403) — Retry, or update yt-dlp in Settings.",
            ErrorHumanizer.humanize("ERROR: unable to download video data: HTTP Error 403: Forbidden"),
        )

    @Test fun messageOnlyWhenThereIsNoHint() =
        assertEquals("The disk is full", ErrorHumanizer.humanize("ERROR: [Errno 28] No space left on device"))

    @Test fun unknownFallsBackToLastLine() =
        assertEquals("second line", ErrorHumanizer.humanize("Some unusual failure\nsecond line"))

    @Test fun unknownErrorLineIsCleaned() =
        assertEquals("Something odd", ErrorHumanizer.humanize("WARNING: noise\nERROR: [generic] Something odd\n"))

    @Test fun warningLineDoesNotDecide() =
        assertEquals(
            "Rate-limited by the site — Wait a few minutes and retry.",
            ErrorHumanizer.humanize(
                "WARNING: [youtube] abc: Sign in to confirm your age\n" +
                    "ERROR: [youtube] abc: Unable to download API page: HTTP Error 429: Too Many Requests",
            ),
        )

    @Test fun urlsDoNotTriggerRules() =
        assertEquals(
            "Something odd at https://example.com/login/rate-limit",
            ErrorHumanizer.humanize("ERROR: Something odd at https://example.com/login/rate-limit"),
        )

    @Test fun legacyExitMessageSurvives() =
        assertEquals("yt-dlp exited 1", ErrorHumanizer.humanize("yt-dlp exited 1"))

    @Test fun blankAndNullGiveDownloadFailed() {
        assertEquals("Download failed", ErrorHumanizer.humanize(""))
        assertEquals("Download failed", ErrorHumanizer.humanize(null))
    }
}
