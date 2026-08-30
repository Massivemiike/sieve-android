package com.sieve.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadStateTest {
    @Test fun wireRoundTrips() =
        DownloadState.entries.forEach { assertEquals(it, DownloadState.from(it.wire)) }

    @Test fun terminalSet() {
        assertTrue(DownloadState.DONE.isTerminal)
        assertTrue(DownloadState.ERROR.isTerminal)
        assertFalse(DownloadState.DOWNLOADING.isTerminal)
        assertFalse(DownloadState.POSTPROCESS.isTerminal)
    }

    @Test fun unknownRejected() {
        assertFailsWith<NoSuchElementException> { DownloadState.from("bogus") }
    }

    @Test fun dashIsEmDash() = assertEquals('—'.toString(), Sentinels.DASH)
}
