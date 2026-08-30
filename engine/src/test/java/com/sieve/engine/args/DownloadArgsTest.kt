package com.sieve.engine.args

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadArgsTest {
    @Test fun prefixOrderNoFfmpegLocation() =
        assertEquals(
            listOf(
                "--newline", "-c", "--no-warnings", "--progress-template",
                DownloadArgs.PROGRESS_TEMPLATE, "-f", "best", "-P", "/out", "U",
            ),
            DownloadArgs.buildCommandTokens("U", listOf("-f", "best", "-P", "/out")),
        )

    @Test fun templateByteExact() = assertEquals(
        "%(progress._percent_str)s|%(progress._speed_str)s|%(progress._eta_str)s|%(progress.fragment_index)s/%(progress.fragment_count)s",
        DownloadArgs.PROGRESS_TEMPLATE,
    )

    @Test fun ensureContinueIdempotent() {
        assertEquals(listOf("-c", "-f", "best"), DownloadArgs.ensureContinue(listOf("-f", "best")))
        assertEquals(listOf("-c", "-f"), DownloadArgs.ensureContinue(listOf("-c", "-f")))
    }

    @Test fun urls() {
        assertTrue(DownloadArgs.isValidUrl("rtmp://s/live"))
        assertTrue(DownloadArgs.isValidUrl("  https://x "))
        assertFalse(DownloadArgs.isValidUrl("yt"))
    }
}
