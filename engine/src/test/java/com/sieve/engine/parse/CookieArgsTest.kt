package com.sieve.engine.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CookieArgsTest {
    @Test fun stripsFlagAndValue() =
        assertEquals(
            listOf("-f", "best"),
            CookieArgs.strip(listOf("--cookies-from-browser", "chrome", "-f", "best")),
        )

    @Test fun stripsBoth() =
        assertEquals(
            listOf("-o", "y"),
            CookieArgs.strip(listOf("--cookies-from-browser", "chrome", "--cookies", "x", "-o", "y")),
        )

    @Test fun usesCookies() {
        assertTrue(CookieArgs.usesCookies(listOf("--cookies", "x")))
        assertFalse(CookieArgs.usesCookies(listOf("-f", "best")))
    }
}
