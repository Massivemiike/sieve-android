package com.sieve.transcode

import com.sieve.transcode.detect.ConcurrencyPlanner
import com.sieve.transcode.detect.RerouteDecider
import com.sieve.transcode.detect.RunningJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 13: reroute + concurrency decisions (Android-collapsed to one HW backend). */
class RerouteConcurrencyTest {

    @Test fun S1_cpuToHw_noHybrid_reroutes() =
        assertTrue(RerouteDecider.isMismatched(RunningJob("cpu", 0.2, "transcoding"), "hw-hevc", hybrid = false))

    @Test fun S2_cpuSpilloverAllowedWhenHybrid() =
        assertFalse(RerouteDecider.isMismatched(RunningJob("cpu", 0.2, "transcoding"), "hw-hevc", hybrid = true))

    @Test fun S3_hwToCpu_reroutes() =
        assertTrue(RerouteDecider.isMismatched(RunningJob("hw-hevc", 0.3, "transcoding"), "cpu", hybrid = false))

    @Test fun S4_overFloor_protected() =
        assertFalse(RerouteDecider.isMismatched(RunningJob("cpu", 0.6, "transcoding"), "hw-hevc", hybrid = false))

    @Test fun S5_cpuStaysCpu() =
        assertFalse(RerouteDecider.isMismatched(RunningJob("cpu", 0.2, "transcoding"), "cpu", hybrid = false))

    @Test fun S6_notTranscodingStateNeverReroutes() =
        assertFalse(RerouteDecider.isMismatched(RunningJob("cpu", 0.2, "queued"), "hw-hevc", hybrid = false))

    @Test fun S7_nullEncoderNeverReroutes() =
        assertFalse(RerouteDecider.isMismatched(RunningJob(null, 0.2, "transcoding"), "hw-hevc", hybrid = false))

    @Test fun concurrency_hwIsTwo_cpuIsHalfCores() {
        assertEquals(2, ConcurrencyPlanner.defaultLimit("hw-h264", 8))
        assertEquals(4, ConcurrencyPlanner.defaultLimit("cpu", 8))
        assertEquals(1, ConcurrencyPlanner.defaultLimit("cpu", 1))
    }

    @Test fun cpuFallbackMax_onlyWhenHybridAndHw() {
        assertEquals(4, ConcurrencyPlanner.cpuFallbackMax(hybrid = true, encoderId = "hw-h264", cores = 8))
        assertEquals(0, ConcurrencyPlanner.cpuFallbackMax(hybrid = false, encoderId = "hw-h264", cores = 8))
        assertEquals(0, ConcurrencyPlanner.cpuFallbackMax(hybrid = true, encoderId = "cpu", cores = 8))
    }
}
