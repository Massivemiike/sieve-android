package com.sieve.transcode

import com.sieve.transcode.args.MediaCodecSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCodecSanitizerTest {

    private val hwCrfArgs = listOf(
        "-c:v", "h264_mediacodec", "-crf", "23", "-preset", "medium",
        "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart",
    )

    @Test
    fun `software encode args pass through untouched`() {
        val sw = listOf("-c:v", "libx264", "-crf", "23", "-preset", "medium")
        assertEquals(sw, MediaCodecSanitizer.sanitize(sw, 1080))
    }

    @Test
    fun `no video codec means no change`() {
        val audio = listOf("-vn", "-c:a", "libmp3lame", "-b:a", "320k")
        assertEquals(audio, MediaCodecSanitizer.sanitize(audio, null))
    }

    @Test
    fun `mediacodec encode strips crf and preset and injects bitrate`() {
        val out = MediaCodecSanitizer.sanitize(hwCrfArgs, 240)
        assertFalse(out.contains("-crf"))
        assertFalse(out.contains("-preset"))
        assertFalse(out.contains("23"))
        assertFalse(out.contains("medium"))
        val b = out.indexOf("-b:v")
        assertTrue(b >= 0 && out[b + 1].endsWith("k"))
        // untouched tail survives in order
        assertTrue(out.containsAll(listOf("-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")))
    }

    @Test
    fun `existing explicit bitrate is respected`() {
        val args = listOf("-c:v", "h264_mediacodec", "-b:v", "5M", "-c:a", "aac")
        assertEquals(args, MediaCodecSanitizer.sanitize(args, 1080))
    }

    @Test
    fun `bitrate ladder scales with height crf and codec`() {
        // 1080p @ CRF 23 baseline
        assertEquals(6000, MediaCodecSanitizer.targetKbps("h264_mediacodec", 1080, 23))
        // lower CRF (higher quality) doubles per -6
        assertEquals(12000, MediaCodecSanitizer.targetKbps("h264_mediacodec", 1080, 17))
        // HEVC at 60%
        assertEquals(3600, MediaCodecSanitizer.targetKbps("hevc_mediacodec", 1080, 23))
        // tiny/unknown height floors sensibly
        assertTrue(MediaCodecSanitizer.targetKbps("h264_mediacodec", 240, 23) >= 300)
        assertTrue(MediaCodecSanitizer.targetKbps("h264_mediacodec", null, null) >= 300)
    }
}
