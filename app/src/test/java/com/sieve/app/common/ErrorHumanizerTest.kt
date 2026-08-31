package com.sieve.app.common

import com.sieve.app.ui.common.ErrorHumanizer
import kotlin.test.Test
import kotlin.test.assertTrue

class ErrorHumanizerTest {

    @Test fun rateLimit() =
        assertTrue(ErrorHumanizer.humanize("ERROR: HTTP Error 429: Too Many Requests").contains("Rate-limited"))

    @Test fun geo() =
        assertTrue(ErrorHumanizer.humanize("This video is geo restricted in your region").contains("Geo-restricted"))

    @Test fun login() =
        assertTrue(ErrorHumanizer.humanize("Sign in to confirm your age").contains("cookies", ignoreCase = true))

    @Test fun format() =
        assertTrue(ErrorHumanizer.humanize("Requested format is not available").contains("format"))

    @Test fun forbidden() =
        assertTrue(ErrorHumanizer.humanize("HTTP Error 403: Forbidden").contains("403"))

    @Test fun unknownFallsBackToFirstLine() =
        assertTrue(ErrorHumanizer.humanize("Some unusual failure\nsecond line").contains("Some unusual failure"))
}
