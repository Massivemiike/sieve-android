package com.sieve.transcode

import com.sieve.transcode.runner.android.SourceProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure part of [SourceProbe]'s duration read (the MediaExtractor/Retriever calls need a device). */
class SourceProbeDurationTest {

    @Test fun `the longest track wins, microseconds to seconds`() {
        assertEquals(125.5, SourceProbe.pickDurationSec(listOf(120_000_000L, 125_500_000L, 90_000_000L)) { error("no fallback needed") }!!, 1e-9)
    }

    @Test fun `non-positive track durations count as absent`() {
        assertEquals(2.0, SourceProbe.pickDurationSec(listOf(0L, -1L, 2_000_000L)) { null }!!, 1e-9)
    }

    @Test fun `falls back to the retriever milliseconds when no track reports a duration`() {
        // KEY_DURATION is optional; MKV/WebM tracks often lack it.
        assertEquals(61.25, SourceProbe.pickDurationSec(emptyList()) { 61_250L }!!, 1e-9)
        assertEquals(61.25, SourceProbe.pickDurationSec(listOf(0L)) { 61_250L }!!, 1e-9)
    }

    @Test fun `unreadable everywhere is null so progress stays indeterminate`() {
        assertNull(SourceProbe.pickDurationSec(emptyList()) { null })
        assertNull(SourceProbe.pickDurationSec(emptyList()) { 0L })
        assertNull(SourceProbe.pickDurationSec(emptyList()) { -5L })
    }

    @Test fun `the retriever is only consulted when the tracks have nothing`() {
        var asked = false
        SourceProbe.pickDurationSec(listOf(1_000_000L)) { asked = true; 9_000L }
        assertTrue(!asked)
    }
}
