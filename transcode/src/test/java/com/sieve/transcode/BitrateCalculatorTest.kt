package com.sieve.transcode

import com.sieve.transcode.output.BitrateCalculator
import com.sieve.transcode.output.OutputEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Task 11: bitrate + size/reduction/ETA math. Note the 8192-vs-1000 dual convention (invariant #14). */
class BitrateCalculatorTest {

    @Test fun videoKbps_targetSizeMinusAudio() {
        assertEquals(1515, BitrateCalculator.videoKbps(25.0, 192.0, 120.0)) // B1
    }

    @Test fun videoKbps_flooredAtZeroWhenTooSmall() {
        assertEquals(0, BitrateCalculator.videoKbps(1.0, 192.0, 600.0)) // B3
    }

    @Test fun videoKbps_nullOnInvalidInputs() {
        assertNull(BitrateCalculator.videoKbps(25.0, 192.0, 0.0)) // B4: zero duration
        assertNull(BitrateCalculator.videoKbps(0.0, 192.0, 120.0)) // zero target
        assertNull(BitrateCalculator.videoKbps(25.0, -1.0, 120.0)) // negative audio
    }

    @Test fun estimatedBytes_crfScalingHalvesAtPlus6() {
        // preset CRF 20 → override 26 = +6 → scale 0.5; 8000 kbps over 120 s → 60 MB
        assertEquals(60_000_000.0, OutputEstimator.estimatedBytes(8000.0, 120.0, 20, 26), 0.001) // E2
    }

    @Test fun estimatedBytes_noScaleWhenCrfUnknown() {
        assertEquals(120_000_000.0, OutputEstimator.estimatedBytes(8000.0, 120.0, null, 26), 0.001)
    }

    @Test fun reductionPct_andEta() {
        assertEquals(50, OutputEstimator.reductionPct(60e6, 120e6)) // E5
        assertEquals(0, OutputEstimator.reductionPct(60e6, 0.0)) // guard
        assertEquals(120.0, OutputEstimator.etaSeconds(120.0, 1.0), 0.0001) // E8
        assertEquals(120.0, OutputEstimator.etaSeconds(120.0, 0.0), 0.0001) // perf 0 → /1
    }
}
