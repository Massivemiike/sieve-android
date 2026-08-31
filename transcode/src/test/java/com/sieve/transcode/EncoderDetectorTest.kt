package com.sieve.transcode

import com.sieve.transcode.detect.DetectionResult
import com.sieve.transcode.detect.EncoderDetector
import com.sieve.transcode.detect.EncoderMapper
import com.sieve.transcode.detect.HardwareEncoderInfo
import com.sieve.transcode.detect.VideoEncoderProbe
import com.sieve.transcode.detect.VideoMime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake platform probe for the pure detector tests. */
private class FakeVideoEncoderProbe(
    private val hw: List<HardwareEncoderInfo>,
    private val ff: Set<String>,
    private val throws: Boolean = false,
) : VideoEncoderProbe {
    override fun hardwareVideoEncoders(): List<HardwareEncoderInfo> {
        if (throws) throw RuntimeException("probe blew up")
        return hw
    }
    override fun ffmpegEncoderNames(): Set<String> {
        if (throws) throw RuntimeException("probe blew up")
        return ff
    }
}

/** Task 12: the double-gated encoder detector. */
class EncoderDetectorTest {

    private val bothHw = listOf(
        HardwareEncoderInfo(VideoMime.H264, "c2.qti.avc.encoder", true),
        HardwareEncoderInfo(VideoMime.HEVC, "c2.qti.hevc.encoder", true),
    )
    private val bothFf = setOf("h264_mediacodec", "hevc_mediacodec")

    private fun detectBoth(saved: String? = null): DetectionResult =
        EncoderDetector(FakeVideoEncoderProbe(bothHw, bothFf), { 8 }).detect(saved)

    private fun detectCpuOnly(cores: Int): DetectionResult =
        EncoderDetector(FakeVideoEncoderProbe(emptyList(), emptySet()), { cores }).detect(null)

    @Test fun D1_bothHwPresent() {
        val r = detectBoth()
        assertEquals(listOf("hw-h264", "hw-hevc", "cpu"), r.encoders.map { it.id })
        assertTrue(r.encoders[0].active)
        assertFalse(r.encoders[1].active)
        assertFalse(r.encoders[2].active)
        assertEquals("hw-h264", r.selected)
    }

    @Test fun D5_doubleGateFailsWithoutFfmpeg() {
        val r = EncoderDetector(
            FakeVideoEncoderProbe(listOf(HardwareEncoderInfo(VideoMime.H264, "x", true)), emptySet()), { 8 },
        ).detect(null)
        assertEquals(listOf("cpu"), r.encoders.map { it.id })
        assertEquals("cpu", r.selected)
        assertTrue(r.encoders[0].active)
    }

    @Test fun D6_savedPrefRespectedIfPresent() {
        assertEquals("cpu", detectBoth(saved = "cpu").selected)
    }

    @Test fun D7_staleSavedFallsToFirst() {
        val r = EncoderDetector(
            FakeVideoEncoderProbe(listOf(HardwareEncoderInfo(VideoMime.H264, "x", true)), setOf("h264_mediacodec")), { 8 },
        ).detect("hw-hevc")
        assertEquals(listOf("hw-h264", "cpu"), r.encoders.map { it.id })
        assertEquals("hw-h264", r.selected)
    }

    @Test fun D8_probeThrowsKeepsCpuDefault() {
        val r = EncoderDetector(FakeVideoEncoderProbe(emptyList(), emptySet(), throws = true), { 8 }).detect(null)
        assertEquals(listOf("cpu"), r.encoders.map { it.id })
        assertEquals("cpu", r.selected)
        assertTrue(r.encoders[0].active)
    }

    @Test fun D9_cpuDevShowsCores() {
        assertEquals("8 cores", detectCpuOnly(cores = 8).encoders[0].dev)
    }

    @Test fun D11_duplicateHwCodecsDedupToOne() {
        val r = EncoderDetector(
            FakeVideoEncoderProbe(
                hw = listOf(
                    HardwareEncoderInfo(VideoMime.H264, "c2.qti.avc.encoder", true),
                    HardwareEncoderInfo(VideoMime.H264, "OMX.google.h264.encoder", true),
                ),
                ff = setOf("h264_mediacodec"),
            ), { 8 },
        ).detect(null)
        assertEquals(listOf("hw-h264", "cpu"), r.encoders.map { it.id }) // exactly one hw-h264
    }

    @Test fun encoderMapper_hwPrefixMapsToMediacodec() {
        assertEquals("h264_mediacodec", EncoderMapper.videoEncoder("hw-h264"))
        assertEquals("hevc_mediacodec", EncoderMapper.hevcEncoder("hw-hevc"))
        assertEquals("libx264", EncoderMapper.videoEncoder("cpu"))
        assertEquals("libx265", EncoderMapper.hevcEncoder("cpu"))
    }
}
