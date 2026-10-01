package com.sieve.transcode

import com.sieve.transcode.args.BuilderEncoder
import com.sieve.transcode.args.FfmpegArgs
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

    // ── -maxrate is a ceiling, never the rate control ────────────────
    private fun hw(id: String) = FfmpegArgs.build(id, BuilderEncoder.HARDWARE)
    private fun kbpsOf(args: List<String>): Int = args[args.indexOf("-b:v") + 1].removeSuffix("k").toInt()

    @Test
    fun `maxrate-only presets still get a bitrate injected and capped at the maxrate`() {
        // h264/h265 1440 and 4K carry -maxrate/-bufsize but no -b:v. ffmpeg's MediaCodec wrappers only read
        // bit_rate (default ~200 kbps) and ignore rc_max_rate, so without an injected -b:v they encode unwatchably.
        val cases = mapOf(
            "h264-1440" to (1440 to 18_000), "h264-4k" to (2160 to 35_000),
            "h265-1440" to (1440 to 12_000), "h265-4k" to (2160 to 22_000),
        )
        for ((id, p) in cases) {
            val (height, capKbps) = p
            val out = MediaCodecSanitizer.sanitize(hw(id), height)
            assertTrue("$id: no -b:v injected", "-b:v" in out)
            val kbps = kbpsOf(out)
            assertTrue("$id: $kbps kbps is not above ffmpeg's 200 kbps default", kbps > 200)
            assertTrue("$id: $kbps kbps exceeds the preset's $capKbps kbps cap", kbps <= capKbps)
            assertFalse("$id: -crf must be stripped", "-crf" in out)
            assertFalse("$id: -preset must be stripped", "-preset" in out)
            assertTrue("$id: -maxrate stays as a (harmless) ceiling", out.containsAll(listOf("-maxrate", "-bufsize")))
        }
        // spot-check the actual ladder values (CRF 20 @ 2160p = 20000 * 2^(3/6))
        assertEquals(28284, kbpsOf(MediaCodecSanitizer.sanitize(hw("h264-4k"), 2160)))
    }

    @Test
    fun `injected bitrate never exceeds the maxrate cap`() {
        // CRF 18 @ 2160p would be ~35.6 Mbps; the 1M cap wins.
        val args = listOf("-c:v", "h264_mediacodec", "-crf", "18", "-maxrate", "1M", "-bufsize", "2M")
        assertEquals(listOf("-c:v", "h264_mediacodec", "-maxrate", "1M", "-bufsize", "2M", "-b:v", "1000k"),
            MediaCodecSanitizer.sanitize(args, 2160))
    }

    @Test
    fun `maxrate accepts M k and plain bps forms`() {
        fun capped(maxrate: String) = kbpsOf(
            MediaCodecSanitizer.sanitize(listOf("-c:v", "h264_mediacodec", "-crf", "18", "-maxrate", maxrate), 2160),
        )
        assertEquals(2500, capped("2.5M"))
        assertEquals(1500, capped("1500k"))
        assertEquals(1500, capped("1500K"))
        assertEquals(2500, capped("2500000"))
    }

    @Test
    fun `unparseable maxrate is ignored and the ladder applies`() {
        val out = MediaCodecSanitizer.sanitize(listOf("-c:v", "h264_mediacodec", "-maxrate", "fast"), 1080)
        assertEquals(6000, kbpsOf(out))
    }

    @Test
    fun `explicit bitrate alongside maxrate is respected`() {
        val args = listOf("-c:v", "h264_mediacodec", "-b:v", "5M", "-maxrate", "6M", "-bufsize", "12M")
        assertEquals(args, MediaCodecSanitizer.sanitize(args, 1080))
    }

    @Test
    fun `duration-derived discord bitrate survives the sanitizer untouched`() {
        // The size-fit -b:v must not be replaced by the height ladder (which would blow the Discord cap).
        val args = FfmpegArgs.build("discord-25", BuilderEncoder.HARDWARE, durationSec = 240.0)
        assertEquals(args, MediaCodecSanitizer.sanitize(args, 2160))
        assertEquals("675908", args[args.indexOf("-b:v") + 1])
    }

    // ── ladder height = the preset's OUTPUT height ──────────────────
    @Test
    fun `ladder follows the preset's scale target instead of the source height`() {
        // 4K phone clip into "H.264 · 720p" (CRF 22): a 720p-class bitrate, not the 4K one (~28 Mbps).
        assertEquals(MediaCodecSanitizer.targetKbps("h264_mediacodec", 720, 22), kbpsOf(MediaCodecSanitizer.sanitize(hw("h264-720"), 2160)))
        assertEquals(3928, kbpsOf(MediaCodecSanitizer.sanitize(hw("h264-720"), 2160)))
        // 4K → "H.264 · 1080p" (CRF 20): ~8.5 Mbps, not ~28.
        assertEquals(8485, kbpsOf(MediaCodecSanitizer.sanitize(hw("h264-1080"), 2160)))
        // upscale: a 480p source sent to 1080p gets the 1080p-class bitrate, not the starved 480p one
        assertEquals(8485, kbpsOf(MediaCodecSanitizer.sanitize(hw("h264-1080"), 480)))
    }

    @Test
    fun `no scale filter keeps the source height`() {
        assertEquals(8485, kbpsOf(MediaCodecSanitizer.sanitize(hw("h264-source"), 1080))) // CRF 20 @ 1080p
        // unknown source and no scale → the 800 kbps default × CRF scale
        assertEquals(1131, kbpsOf(MediaCodecSanitizer.sanitize(hw("h264-source"), null)))
    }

    @Test
    fun `scale parsing handles W-H pad chains extra options and filter chains`() {
        fun ladderFor(vf: String, src: Int?) = kbpsOf(
            MediaCodecSanitizer.sanitize(listOf("-c:v", "h264_mediacodec", "-vf", vf), src),
        )
        // explicit WxH (ig-vert style): height 1920 → the 1440+ bucket; CRF absent → scale 1.0
        assertEquals(10000, ladderFor("scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2", 480))
        // trailing scale options
        assertEquals(3500, ladderFor("scale=-2:720:flags=lanczos", 2160))
        // scale buried in a chain (burned subtitles are appended after it)
        assertEquals(3500, ladderFor("fps=30,scale=-2:720,subtitles='a,b.srt'", 2160))
        // width-driven scale: the output height is unknowable here → fall back to the source height
        assertEquals(6000, ladderFor("scale=1280:-2", 1080))
        // no scale in the chain at all
        assertEquals(6000, ladderFor("subtitles='a.srt'", 1080))
        // an expression instead of a literal height → fall back
        assertEquals(6000, ladderFor("scale=-2:ih/2", 1080))
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
