package com.sieve.engine.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorMapperTest {
    @Test fun extractLastErrorWins() =
        assertEquals("ERROR: second", ErrorMapper.extract(listOf("ERROR: first", "[i]…", "ERROR: second"), 1))

    @Test fun extractCodeFallback() =
        assertEquals("yt-dlp exited with code 1", ErrorMapper.extract(emptyList(), 1))

    @Test fun ageSubstringFootgun() =
        assertEquals(
            "Login required — set cookies-from-browser in Settings",
            ErrorMapper.humanize("ERROR: Unable to extract message"),
        )

    @Test fun humanizeErasesThrottleTransient() {
        val h = ErrorMapper.humanize("ERROR: throttled download")
        assertEquals("Rate-limited by site — try again later or use a proxy", h)
        assertFalse(ErrorMapper.isTransient(h))
    }

    @Test fun networkStaysTransient() =
        assertTrue(ErrorMapper.isTransient(ErrorMapper.humanize("ERROR: Network unreachable")))
}
