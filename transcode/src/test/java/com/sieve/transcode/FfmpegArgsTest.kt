package com.sieve.transcode

import com.sieve.transcode.args.BuilderEncoder.HARDWARE
import com.sieve.transcode.args.BuilderEncoder.SOFTWARE
import com.sieve.transcode.args.EncoderResolver
import com.sieve.transcode.args.FfmpegArgs
import com.sieve.transcode.catalog.TranscodePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 2 + Task 4: encoder resolution and the byte-exact arg vectors. */
class FfmpegArgsTest {

    // ── Task 2: encoder resolution ──────────────────────────────────
    @Test fun encoderResolverMapsSoftwareAndHardware() {
        assertEquals("libx264", EncoderResolver.h264(SOFTWARE))
        assertEquals("h264_mediacodec", EncoderResolver.h264(HARDWARE))
        assertEquals("libx265", EncoderResolver.hevc(SOFTWARE))
        assertEquals("hevc_mediacodec", EncoderResolver.hevc(HARDWARE))
    }

    // ── Task 4: representative byte-exact vectors, one per shape ─────
    @Test fun h264_1080_software() {
        assertEquals(
            listOf("-c:v", "libx264", "-crf", "20", "-preset", "medium", "-vf", "scale=-2:1080",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"),
            FfmpegArgs.build("h264-1080", SOFTWARE),
        )
    }

    @Test fun h264_1080_hardware_swapsOnlyTheVideoToken() {
        assertEquals(
            listOf("-c:v", "h264_mediacodec", "-crf", "20", "-preset", "medium", "-vf", "scale=-2:1080",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"),
            FfmpegArgs.build("h264-1080", HARDWARE),
        )
    }

    @Test fun h264_4k_maxrateAndBufsizePrecedeVf() {
        val args = FfmpegArgs.build("h264-4k", SOFTWARE)
        assertEquals(
            listOf("-c:v", "libx264", "-crf", "20", "-preset", "medium", "-maxrate", "35M", "-bufsize", "70M",
                "-vf", "scale=-2:2160", "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart"),
            args,
        )
        // token-order invariant: -maxrate/-bufsize come before -vf
        assertTrue(args.indexOf("-maxrate") < args.indexOf("-vf"))
        assertTrue(args.indexOf("-bufsize") < args.indexOf("-vf"))
    }

    @Test fun hevc_1080_appendsHvc1Tag() {
        assertEquals(
            listOf("-c:v", "libx265", "-crf", "23", "-preset", "medium", "-vf", "scale=-2:1080",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-tag:v", "hvc1"),
            FfmpegArgs.build("h265-1080", SOFTWARE),
        )
    }

    @Test fun av1_isNeverHardwareRouted() {
        val sw = FfmpegArgs.build("av1-1080", SOFTWARE)
        val hw = FfmpegArgs.build("av1-1080", HARDWARE)
        assertEquals(sw, hw) // GPU selection does not touch AV1
        assertEquals(
            listOf("-c:v", "libsvtav1", "-crf", "30", "-preset", "6", "-vf", "scale=-2:1080",
                "-c:a", "libopus", "-b:a", "128k"),
            sw,
        )
    }

    @Test fun vp9_source_usesCrfPlusBv0_whileWebmVp9IsPureBitrate() {
        assertEquals(
            listOf("-c:v", "libvpx-vp9", "-crf", "31", "-b:v", "0", "-row-mt", "1",
                "-c:a", "libopus", "-b:a", "128k"),
            FfmpegArgs.build("vp9-source", SOFTWARE),
        )
        assertEquals(
            listOf("-c:v", "libvpx-vp9", "-b:v", "5M", "-row-mt", "1", "-vf", "scale=-2:1080",
                "-c:a", "libopus", "-b:a", "128k"),
            FfmpegArgs.build("webm-vp9", SOFTWARE),
        )
    }

    @Test fun prores4444_carriesPixFmtAndPcmAudio() {
        assertEquals(
            listOf("-c:v", "prores_ks", "-profile:v", "4", "-pix_fmt", "yuva444p10le", "-c:a", "pcm_s16le"),
            FfmpegArgs.build("prores-4444", SOFTWARE),
        )
    }

    @Test fun yt1080_useBitrateAndPresetSlow_presetPrecedesVf() {
        val args = FfmpegArgs.build("yt-1080", SOFTWARE)
        assertEquals(
            listOf("-c:v", "libx264", "-b:v", "12M", "-preset", "slow", "-vf", "scale=-2:1080",
                "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart"),
            args,
        )
        assertTrue(args.indexOf("-preset") < args.indexOf("-vf"))
    }

    @Test fun igVert_padFilterIsVerbatim() {
        assertEquals(
            listOf("-c:v", "libx264", "-b:v", "12M", "-vf",
                "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"),
            FfmpegArgs.build("ig-vert", SOFTWARE),
        )
    }

    @Test fun discord25_usesFsCap() {
        assertEquals(
            listOf("-c:v", "libx264", "-fs", "25M", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart"),
            FfmpegArgs.build("discord-25", SOFTWARE),
        )
    }

    @Test fun audioPresetsLeadWithVn_noVideoCodec() {
        assertEquals(listOf("-vn", "-c:a", "libmp3lame", "-b:a", "320k"), FfmpegArgs.build("mp3-320", SOFTWARE))
        assertEquals(listOf("-vn", "-c:a", "flac"), FfmpegArgs.build("flac", SOFTWARE))
        assertEquals(listOf("-vn", "-c:a", "pcm_s16le"), FfmpegArgs.build("wav", SOFTWARE))
    }

    @Test fun rokuFire_carriesFullBt709ColorMetadata() {
        assertEquals(
            listOf("-c:v", "libx265", "-crf", "24", "-preset", "medium", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart", "-tag:v", "hvc1",
                "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709"),
            FfmpegArgs.build("roku-fire", SOFTWARE),
        )
    }

    @Test fun dvdNtsc_fixedMpeg2video_fDvdAtEnd() {
        val args = FfmpegArgs.build("dvd-ntsc", SOFTWARE)
        assertEquals(
            listOf("-c:v", "mpeg2video", "-vf", "scale=720:480", "-r", "29.97", "-b:v", "6M",
                "-c:a", "ac3", "-b:a", "192k", "-f", "dvd"),
            args,
        )
        assertEquals(listOf("-f", "dvd"), args.takeLast(2))
    }

    @Test fun gif_hasNoCv_whileWebpAnimUsesVcodec() {
        assertEquals(
            listOf("-vf", "fps=12,scale=480:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse",
                "-loop", "0"),
            FfmpegArgs.build("gif", SOFTWARE),
        )
        assertTrue("gif must not carry -c:v", "-c:v" !in FfmpegArgs.build("gif", SOFTWARE))
        assertEquals(
            listOf("-vcodec", "libwebp", "-vf", "fps=24", "-quality", "80", "-loop", "0"),
            FfmpegArgs.build("webp-anim", SOFTWARE),
        )
    }

    // ── Trim prefix ─────────────────────────────────────────────────
    @Test fun trimPushesIntegerSecondsBeforeCodecArgs() {
        // duration 100s, trimIn 0.345 → floor(34.5)=34 ; trimOut 0.90 → floor(90)=90
        val args = FfmpegArgs.build("aac-256", SOFTWARE, trimIn = 0.345, trimOut = 0.90, durationSec = 100.0)
        assertEquals(listOf("-ss", "34", "-to", "90", "-vn", "-c:a", "aac", "-b:a", "256k"), args)
    }

    @Test fun defaultTrimEmitsNoSsOrTo() {
        val args = FfmpegArgs.build("aac-256", SOFTWARE, trimIn = 0.0, trimOut = 1.0, durationSec = 100.0)
        assertEquals(listOf("-vn", "-c:a", "aac", "-b:a", "256k"), args)
    }

    // ── Unknown / custom → trim-only ────────────────────────────────
    @Test fun customIdReturnsTrimOnlyArgs() {
        assertEquals(emptyList<String>(), FfmpegArgs.build("custom-xyz", SOFTWARE))
        assertEquals(
            listOf("-ss", "5"),
            FfmpegArgs.build("custom-xyz", SOFTWARE, trimIn = 0.05, durationSec = 100.0),
        )
    }

    // ── Whole-catalog coverage ──────────────────────────────────────
    @Test fun every52PresetBuildsNonEmptyArgs() {
        for (preset in TranscodePresets.all) {
            val args = FfmpegArgs.build(preset.id, SOFTWARE)
            assertTrue("preset ${preset.id} produced empty args", args.isNotEmpty())
        }
    }

    @Test fun onlyH264HevcAndDeviceFamiliesRespondToHardwareToggle() {
        // Every preset that differs SW vs HW must be an H.264/HEVC-encoded arm.
        val changed = TranscodePresets.all.filter {
            FfmpegArgs.build(it.id, SOFTWARE) != FfmpegArgs.build(it.id, HARDWARE)
        }.map { it.id }.toSet()
        val expected = setOf(
            "h264-source", "h264-720", "h264-1080", "h264-1440", "h264-4k",
            "h265-source", "h265-720", "h265-1080", "h265-1440", "h265-4k",
            "yt-source", "yt-720", "yt-1080", "yt-1440", "yt-4k",
            "ig-vert", "ig-square", "twitter", "discord-25", "discord-8",
            "apple-iphone", "apple-ipad", "apple-tv", "android-mobile", "android-tablet",
            "roku-fire", "plex-direct",
        )
        assertEquals(expected, changed)
    }
}
