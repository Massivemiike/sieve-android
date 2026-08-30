package com.sieve.engine.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ProgressParserTest {
    @Test fun formatAWithEmptyFieldsBecomeDash() {
        val p = ProgressParser.parseProgress("  10.0%|||")!!
        assertEquals(0.1f, p.percent)
        assertEquals("—", p.speed)
    }

    @Test fun formatANaNPercentGuardsToZero() =
        assertEquals(0.0f, ProgressParser.parseProgress("  NA%| 1.17MiB/s|00:13|NA/NA")!!.percent)

    @Test fun fourPartsNoPercentIsNull() = assertNull(ProgressParser.parseProgress("a|b|c|d"))

    @Test fun formatBDefaultLine() {
        val p = ProgressParser.parseProgress("[download]  50.1% of 15.97MiB at 22.81MiB/s ETA 00:00")!!
        assertEquals(0.501f, p.percent)
        assertEquals("22.81MiB/s", p.speed)
        assertEquals("—", p.fragment)
    }

    @Test fun tildeSizeDoesNotMatchB() =
        assertNull(ProgressParser.parseProgress("[download]  45.2% of ~120.00MiB at 5.00MiB/s ETA 00:20"))

    @Test fun metadataDoesNotTriggerPostprocessButExtractsPath() {
        assertFalse(ProgressParser.isPostProcess("[Metadata] Destination: x"))
        assertEquals("/a/b.mp4", ProgressParser.parseFilePath("[Metadata] Destination: /a/b.mp4"))
    }

    @Test fun mergerIsPostProcess() =
        kotlin.test.assertTrue(ProgressParser.isPostProcess("[Merger] Merging formats into \"x.mkv\""))

    @Test fun partialAnsiStripLeavesEsc() =
        assertEquals("ERR", ProgressParser.cleanLogLine("[0;31mERR[0m"))

    @Test fun logCapAt500() {
        val s = "x".repeat(501)
        assertEquals(s.take(500) + "… (truncated)", ProgressParser.cleanLogLine(s))
    }

    @Test fun parseSizeRelabelsUnit() =
        assertEquals("1.2 GB", ProgressParser.parseSize("[download]  45% of 1.23GiB at 5MiB/s ETA 00:20"))
}
