package com.sieve.transcode

import com.sieve.transcode.args.BuilderEncoder.HARDWARE
import com.sieve.transcode.args.BuilderEncoder.SOFTWARE
import com.sieve.transcode.args.ArgFinalizer
import com.sieve.transcode.args.EncoderResolver
import com.sieve.transcode.args.FfmpegArgs
import com.sieve.transcode.args.FinalizeOptions
import com.sieve.transcode.args.LoudnormRate
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
        // -pix_fmt yuv420p right after the rate-control/-preset tokens (desktop encoderMap order).
        assertEquals(
            listOf("-c:v", "libx264", "-crf", "20", "-preset", "medium", "-pix_fmt", "yuv420p", "-vf", "scale=-2:1080",
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
                "-pix_fmt", "yuv420p", "-vf", "scale=-2:2160", "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart"),
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

    @Test fun dnxhrHq_forces8bit422PixFmtRightAfterProfile() {
        // DNxHR HQ/SQ are 8-bit 4:2:2 only; a 10-bit 4:2:0 (HDR10 HEVC) source can't open the encoder otherwise.
        assertEquals(
            listOf("-c:v", "dnxhd", "-profile:v", "dnxhr_hq", "-pix_fmt", "yuv422p", "-c:a", "pcm_s16le", "-ar", "48000"),
            FfmpegArgs.build("dnxhr-hq", SOFTWARE),
        )
    }

    @Test fun dnxhrSq_forces8bit422PixFmtRightAfterProfile() {
        assertEquals(
            listOf("-c:v", "dnxhd", "-profile:v", "dnxhr_sq", "-pix_fmt", "yuv422p", "-c:a", "pcm_s16le", "-ar", "48000"),
            FfmpegArgs.build("dnxhr-sq", SOFTWARE),
        )
    }

    @Test fun dnxhr444_keepsItsOwn10bitPixFmt() {
        assertEquals(
            listOf("-c:v", "dnxhd", "-profile:v", "dnxhr_444", "-pix_fmt", "yuv444p10le", "-c:a", "pcm_s16le", "-ar", "48000"),
            FfmpegArgs.build("dnxhr-444", SOFTWARE),
        )
    }

    @Test fun dnxhrMxfAudioIsPinnedTo48kHz_evenWithNormalize() {
        // ffmpeg's MXF muxer only writes 48 kHz audio ("only 48khz is implemented", exit -1/EPERM);
        // YouTube/most phone audio is 44.1 kHz. -ar must survive loudnorm's aresample restore.
        val normalized = LoudnormRate.restore(
            ArgFinalizer.finalize(
                FfmpegArgs.build("dnxhr-hq", SOFTWARE),
                FinalizeOptions(requestedThreads = 4, emitThreads = true, normalizeAudio = true),
            ),
            44100,
        )
        val ar = normalized.indexOf("-ar")
        assertEquals("48000", normalized[ar + 1])
    }

    @Test fun yt1080_useBitrateAndPresetSlow_presetPrecedesVf() {
        val args = FfmpegArgs.build("yt-1080", SOFTWARE)
        assertEquals(
            listOf("-c:v", "libx264", "-b:v", "12M", "-preset", "slow", "-pix_fmt", "yuv420p", "-vf", "scale=-2:1080",
                "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart"),
            args,
        )
        assertTrue(args.indexOf("-preset") < args.indexOf("-vf"))
    }

    @Test fun igVert_padFilterIsVerbatim() {
        assertEquals(
            listOf("-c:v", "libx264", "-b:v", "12M", "-pix_fmt", "yuv420p", "-vf",
                "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"),
            FfmpegArgs.build("ig-vert", SOFTWARE),
        )
    }

    // ── Discord size caps: fit the WHOLE clip; -fs is only a safety ceiling ──
    @Test fun discord25_unknownDuration_fallsBackToFsCapOnly() {
        // durationSec <= 0 → no bitrate can be derived; keep the -fs ceiling (desktop discordFitArgs parity).
        assertEquals(
            listOf("-c:v", "libx264", "-fs", "25M", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart"),
            FfmpegArgs.build("discord-25", SOFTWARE),
        )
        assertEquals(
            FfmpegArgs.build("discord-25", SOFTWARE),
            FfmpegArgs.build("discord-25", SOFTWARE, durationSec = -1.0),
        )
    }

    @Test fun discord25_knownDuration_targetsABitrateThatFitsTheCap() {
        // 25 MiB * 8 * 0.92 / 240 s = 803908 bps total, minus 128 kbps audio = 675908 bps of video.
        assertEquals(
            listOf("-c:v", "libx264", "-b:v", "675908", "-maxrate", "675908", "-bufsize", "1351816", "-fs", "25M",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart"),
            FfmpegArgs.build("discord-25", SOFTWARE, durationSec = 240.0),
        )
    }

    @Test fun discord8_knownDuration_usesTheSmallerBudgetAndAudio() {
        // 8 MiB * 8 * 0.92 / 60 s = 1029002 bps total, minus 96 kbps audio = 933002 bps of video.
        assertEquals(
            listOf("-c:v", "libx264", "-b:v", "933002", "-maxrate", "933002", "-bufsize", "1866004", "-fs", "8M",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart"),
            FfmpegArgs.build("discord-8", SOFTWARE, durationSec = 60.0),
        )
    }

    @Test fun discord_videoBitrateFloorsAt200kbps() {
        // 8 MiB over 4 minutes leaves only 161250 bps of video → clamped to the 200 kbps floor.
        val args = FfmpegArgs.build("discord-8", SOFTWARE, durationSec = 240.0)
        assertEquals("200000", args[args.indexOf("-b:v") + 1])
        assertEquals("200000", args[args.indexOf("-maxrate") + 1])
        assertEquals("400000", args[args.indexOf("-bufsize") + 1])
    }

    @Test fun discord_hardwareGetsTheSameRateControlAndNoPixFmt() {
        // MediaCodec only consumes -b:v; the sanitizer leaves it alone because -b:v is already present.
        assertEquals(
            listOf("-c:v", "h264_mediacodec", "-b:v", "933002", "-maxrate", "933002", "-bufsize", "1866004", "-fs", "8M",
                "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart"),
            FfmpegArgs.build("discord-8", HARDWARE, durationSec = 60.0),
        )
    }

    @Test fun discord_budgetUsesTheTrimmedLengthNotTheWholeSource() {
        // trim 25%..75% of 400 s → -ss 100 -to 300 → a 200 s output: 192937984 bits / 200 s = 964689 bps,
        // minus 128 kbps audio = 836689. (Divergence from the desktop, which budgets the untrimmed length.)
        val args = FfmpegArgs.build("discord-25", SOFTWARE, trimIn = 0.25, trimOut = 0.75, durationSec = 400.0)
        assertEquals(listOf("-ss", "100", "-to", "300", "-c:v", "libx264", "-b:v", "836689"), args.take(8))
    }

    @Test fun discord_estimatedSizeStaysUnderTheCapForTypicalDurations() {
        for ((id, capMiB, audioBps) in listOf(Triple("discord-25", 25, 128_000), Triple("discord-8", 8, 96_000))) {
            for (sec in listOf(5, 30, 60, 120, 300, 600)) {
                val args = FfmpegArgs.build(id, SOFTWARE, durationSec = sec.toDouble())
                val vbps = args[args.indexOf("-b:v") + 1].toLong()
                if (vbps == 200_000L) continue // floored: the -fs ceiling is the only guard, by design
                val bytes = (vbps + audioBps) * sec / 8.0
                assertTrue("$id ${sec}s estimated $bytes B exceeds the cap", bytes <= capMiB * 1024.0 * 1024.0)
            }
        }
    }

    // ── 8-bit 4:2:0 for software H.264 (broad playback) ─────────────
    private fun videoCodecOf(args: List<String>): String? = args.indexOf("-c:v").let { if (it >= 0) args[it + 1] else null }

    @Test fun everySoftwareH264PresetForces8bit420PixFmt() {
        // A 10-bit / 4:2:2 / 4:4:4 source otherwise yields High10/4:2:2 H.264 that iOS, TVs and uploads reject.
        val h264 = TranscodePresets.all.filter { videoCodecOf(FfmpegArgs.build(it.id, SOFTWARE)) == "libx264" }
        assertEquals(19, h264.size)
        for (p in h264) {
            val args = FfmpegArgs.build(p.id, SOFTWARE)
            assertEquals("${p.id} must carry exactly one -pix_fmt", 1, args.count { it == "-pix_fmt" })
            assertEquals("${p.id}", "yuv420p", args[args.indexOf("-pix_fmt") + 1])
        }
    }

    @Test fun pixFmtSitsAfterTheRateControlBlockAndBeforeTheRest() {
        assertEquals(
            listOf("-c:v", "libx264", "-crf", "20", "-preset", "medium", "-pix_fmt", "yuv420p",
                "-profile:v", "high", "-level", "4.2", "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart"),
            FfmpegArgs.build("plex-direct", SOFTWARE),
        )
        // no rate-control tokens at all → straight after the codec
        assertEquals(
            listOf("-c:v", "libx264", "-pix_fmt", "yuv420p", "-vf", "scale=-2:1080",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"),
            FfmpegArgs.build("apple-iphone", SOFTWARE),
        )
    }

    @Test fun hardwareH264PresetsNeverCarryPixFmt() {
        // h264_mediacodec only accepts nv12/mediacodec frames; a forced yuv420p just triggers a reformat/failure.
        for (p in TranscodePresets.all) {
            val args = FfmpegArgs.build(p.id, HARDWARE)
            if (videoCodecOf(args) == "h264_mediacodec") assertTrue("${p.id} (HW) must not force -pix_fmt", "-pix_fmt" !in args)
        }
    }

    @Test fun withSoftwarePixFmt_isIdempotentAndLeavesOtherCodecsAlone() {
        val sw = FfmpegArgs.build("h264-1080", SOFTWARE)
        assertEquals(sw, FfmpegArgs.withSoftwarePixFmt(sw))
        val hevc = FfmpegArgs.build("h265-1080", SOFTWARE)
        assertEquals(hevc, FfmpegArgs.withSoftwarePixFmt(hevc))
        val audio = FfmpegArgs.build("mp3-320", SOFTWARE)
        assertEquals(audio, FfmpegArgs.withSoftwarePixFmt(audio))
    }

    @Test fun audioPresetsLeadWithVn_noVideoCodec() {
        assertEquals(listOf("-vn", "-c:a", "libmp3lame", "-b:a", "320k"), FfmpegArgs.build("mp3-320", SOFTWARE))
        // fixed 4608-sample block size: newer ffmpeg follows the decoder's frame size otherwise
        assertEquals(listOf("-vn", "-c:a", "flac", "-frame_size", "4608"), FfmpegArgs.build("flac", SOFTWARE))
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
