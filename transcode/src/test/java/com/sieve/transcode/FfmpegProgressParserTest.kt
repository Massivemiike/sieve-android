package com.sieve.transcode

import com.sieve.transcode.runner.FfmpegProgressParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 15: ffmpeg -progress parser. out_time_ms is REALLY microseconds; chunks split anywhere. */
class FfmpegProgressParserTest {

    @Test fun P2_outTimeMsIsMicroseconds() {
        val e = FfmpegProgressParser(120.0)
            .onChunk("out_time_ms=4000000\nspeed=1x\nprogress=continue\n").single()
        assertEquals(4_000_000L, e.outTimeUs)
        assertEquals(0.0333, e.percent!!, 0.001) // ÷1_000_000, not ÷1000
        assertEquals(1.0, e.speed!!, 0.0001)
    }

    @Test fun P4_percentClampedToOne() {
        val e = FfmpegProgressParser(60.0)
            .onChunk("out_time_us=90000000\nprogress=continue\n").single()
        assertEquals(1.0, e.percent!!, 0.0)
    }

    @Test fun P6_speedNaIsNullButRawKept() {
        val e = FfmpegProgressParser(60.0)
            .onChunk("out_time_us=5000000\nspeed=N/A\nprogress=continue\n").single()
        assertNull(e.speed)
        assertEquals("N/A", e.speedRaw)
    }

    @Test fun P8_chunkSplitAcrossReadsIsCarried() {
        val p = FfmpegProgressParser(60.0)
        assertTrue(p.onChunk("out_ti").isEmpty())
        assertEquals(1_000_000L, p.onChunk("me_us=1000000\nprogress=continue\n").single().outTimeUs)
    }

    @Test fun P9_endBlockFlagged() {
        val e = FfmpegProgressParser(60.0)
            .onChunk("out_time_us=60000000\nprogress=end\n").single()
        assertTrue(e.isEnd)
        assertEquals(1.0, e.percent!!, 0.0001)
    }

    @Test fun P10_nullDurationYieldsNullPercent() {
        val e = FfmpegProgressParser(null)
            .onChunk("out_time_us=5000000\nprogress=continue\n").single()
        assertNull(e.percent)
    }

    @Test fun P11_parseSpeed() {
        assertEquals(1.02, FfmpegProgressParser.parseSpeed("1.02x")!!, 0.0001)
        assertNull(FfmpegProgressParser.parseSpeed("N/A"))
        assertEquals(2.0, FfmpegProgressParser.parseSpeed(" 2x ")!!, 0.0001)
        assertNull(FfmpegProgressParser.parseSpeed(""))
        assertNull(FfmpegProgressParser.parseSpeed(null))
    }

    @Test fun parseTimecodeUs_hhmmssFraction() {
        assertEquals(3_661_500_000L, FfmpegProgressParser.parseTimecodeUs("01:01:01.500000"))
        assertEquals(1_000_000L, FfmpegProgressParser.parseTimecodeUs("00:00:01"))
        assertNull(FfmpegProgressParser.parseTimecodeUs("nope"))
    }

    @Test fun outTimePrecedence_usBeatsMs() {
        val e = FfmpegProgressParser(120.0)
            .onChunk("out_time_ms=9999\nout_time_us=7000000\nprogress=continue\n").single()
        assertEquals(7_000_000L, e.outTimeUs)
    }
}
