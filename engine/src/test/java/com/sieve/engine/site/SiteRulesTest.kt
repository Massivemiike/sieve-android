package com.sieve.engine.site

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SiteRulesNormalizeUrlTest {
    @Test fun linkedinCountrySubdomainBecomesWww() = assertEquals(
        "https://www.linkedin.com/posts/the-mathworks_2_what-is-mathworks-cloud-center-activity-7151241570371948544-4Gu7",
        SiteRules.normalizeUrl("https://uk.linkedin.com/posts/the-mathworks_2_what-is-mathworks-cloud-center-activity-7151241570371948544-4Gu7"),
    )

    @Test fun linkedinEmbedFeedUpdateDropsTheEmbedSegment() = assertEquals(
        "https://www.linkedin.com/feed/update/urn:li:activity:7151241570371948544",
        SiteRules.normalizeUrl("https://www.linkedin.com/embed/feed/update/urn:li:activity:7151241570371948544"),
    )

    @Test fun linkedinCountrySubdomainAndEmbedTogether() = assertEquals(
        "https://www.linkedin.com/feed/update/urn:li:ugcPost:1?x=1#frag",
        SiteRules.normalizeUrl("https://ca.linkedin.com/embed/feed/update/urn:li:ugcPost:1?x=1#frag"),
    )

    @Test fun linkedinWwwUrlIsUnchanged() {
        val u = "https://www.linkedin.com/posts/someone_x-activity-1-abc"
        assertEquals(u, SiteRules.normalizeUrl(u))
    }

    @Test fun linkedinNonCountryHostIsKept() {
        val u = "https://business.linkedin.com/marketing"
        assertEquals(u, SiteRules.normalizeUrl(u))
    }

    @Test fun percentEscapesAreNotReEncoded() {
        val u = "https://uk.linkedin.com/posts/a%C3%A9_b-activity-1?utm_source=a%20b"
        assertEquals("https://www.linkedin.com/posts/a%C3%A9_b-activity-1?utm_source=a%20b", SiteRules.normalizeUrl(u))
    }

    @Test fun facebookEmbedPlayerUnwrapsTheHref() = assertEquals(
        "https://www.facebook.com/reel/1195289147628387/",
        SiteRules.normalizeUrl("https://www.facebook.com/plugins/video.php?href=https%3A%2F%2Fwww.facebook.com%2Freel%2F1195289147628387%2F&show_text=false&width=267"),
    )

    @Test fun facebookEmbedPlayerWithoutHrefIsUnchanged() {
        val u = "https://www.facebook.com/plugins/video.php?show_text=false"
        assertEquals(u, SiteRules.normalizeUrl(u))
    }

    @Test fun facebookEmbedPlayerWithNonHttpHrefIsUnchanged() {
        val u = "https://www.facebook.com/plugins/video.php?href=javascript%3Aalert(1)"
        assertEquals(u, SiteRules.normalizeUrl(u))
    }

    @Test fun facebookOrdinaryUrlIsUnchanged() {
        val u = "https://www.facebook.com/watch/?v=123"
        assertEquals(u, SiteRules.normalizeUrl(u))
    }

    @Test fun youtubeUrlIsUnchanged() {
        val u = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1"
        assertEquals(u, SiteRules.normalizeUrl(u))
    }

    @Test fun otherInputIsReturnedTrimmed() {
        assertEquals("https://example.com/a", SiteRules.normalizeUrl("  https://example.com/a \n"))
        assertEquals("not a url", SiteRules.normalizeUrl(" not a url "))
    }
}

class SiteRulesFallbackUrlTest {
    private val wall = "ERROR: [vimeo] 98044508: The web client only works when logged-in. Use --cookies"

    @Test fun vimeoPageUrlFallsBackToThePlayerUrl() =
        assertEquals("https://player.vimeo.com/video/98044508", SiteRules.fallbackUrl("https://vimeo.com/98044508", wall))

    @Test fun unlistedLinkKeepsItsHash() = assertEquals(
        "https://player.vimeo.com/video/123456789?h=abcdef0123",
        SiteRules.fallbackUrl("https://vimeo.com/123456789/abcdef0123", wall),
    )

    @Test fun showcaseUrlTakesTheLastNumericId() = assertEquals(
        "https://player.vimeo.com/video/98044508",
        SiteRules.fallbackUrl("https://vimeo.com/showcase/1234567/video/98044508", wall),
    )

    @Test fun albumUrlTakesTheLastNumericId() = assertEquals(
        "https://player.vimeo.com/video/98044508",
        SiteRules.fallbackUrl("https://vimeo.com/album/3456789/video/98044508?autoplay=1", wall),
    )

    @Test fun queryAndFragmentAreIgnored() {
        assertEquals("https://player.vimeo.com/video/98044508", SiteRules.fallbackUrl("https://vimeo.com/98044508?ref=x", wall))
        assertEquals("https://player.vimeo.com/video/98044508", SiteRules.fallbackUrl("https://vimeo.com/98044508#t=5", wall))
    }

    @Test fun errorMatchIsCaseInsensitive() =
        assertEquals(
            "https://player.vimeo.com/video/98044508",
            SiteRules.fallbackUrl("https://VIMEO.com/98044508", "error: the Web Client Only Works When Logged-In"),
        )

    @Test fun noMatchingErrorMeansNoFallback() {
        assertNull(SiteRules.fallbackUrl("https://vimeo.com/98044508", "ERROR: Video unavailable"))
        assertNull(SiteRules.fallbackUrl("https://vimeo.com/98044508", ""))
    }

    @Test fun playerUrlIsNeverRewritten() =
        assertNull(SiteRules.fallbackUrl("https://player.vimeo.com/video/98044508", wall))

    @Test fun nonVimeoUrlHasNoFallback() =
        assertNull(SiteRules.fallbackUrl("https://example.com/video/98044508", wall))

    @Test fun shortNumbersAreNotVideoIds() =
        assertNull(SiteRules.fallbackUrl("https://vimeo.com/channels/1234", wall))
}

class SiteRulesErrorTextTest {
    @Test fun dropsWarningLines() = assertEquals(
        "ERROR: [vimeo] 1: DRM protected",
        SiteRules.errorText("WARNING: [vimeo] 1: something\n[info] noise\nERROR: [vimeo] 1: DRM protected\n"),
    )

    @Test fun stripsUrlsFromTheKeptLines() {
        val text = SiteRules.errorText("ERROR: [generic] Unsupported URL: https://example.com/login?x=1 (sign in)")
        assertFalse("example.com" in text)
        assertEquals("ERROR: [generic] Unsupported URL:   (sign in)", text)
    }

    @Test fun keepsEveryErrorLineJoinedByNewline() = assertEquals(
        "ERROR: first\nERROR: second",
        SiteRules.errorText("ERROR: first\nnoise\r\nERROR: second"),
    )

    @Test fun nullAndBlankGiveEmpty() {
        assertEquals("", SiteRules.errorText(null))
        assertEquals("", SiteRules.errorText("  \n "))
    }

    @Test fun loginRequiredFailuresAreRecognised() {
        for (e in listOf(
            "ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate",
            "ERROR: [youtube] abc: Sign in to confirm you're not a bot",
            "ERROR: [vimeo] 1: The web client only works when logged-in. Use --cookies-from-browser or --cookies",
            "ERROR: [instagram] x: Requested content is not available, rate-limit reached or login required",
            "ERROR: [twitter] 1: This tweet is from a private account",
            "ERROR: Unable to download JSON metadata: HTTP Error 401: Unauthorized",
            "ERROR: [youtube] abc: Join this channel to get access to members-only content",
        )) assertTrue(SiteRules.looksLoginRequired(e), e)
    }

    @Test fun otherFailuresAreNotLoginRequired() {
        for (e in listOf(
            "ERROR: [generic] Unsupported URL: https://example.com/login-tips-video",   // a URL slug is not a verdict
            "ERROR: [youtube] abc: Video unavailable",
            "ERROR: Unable to download webpage: HTTP Error 429: Too Many Requests",
            "WARNING: Sign in to confirm your age. Some formats may be missing",           // WARNING lines never decide
            "",
        )) assertFalse(SiteRules.looksLoginRequired(e), e)
        assertFalse(SiteRules.looksLoginRequired(null))
    }
}
