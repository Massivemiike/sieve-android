package com.sieve.transcode.args

import kotlin.math.floor

/**
 * Which encoder tier the arg-builder resolves `<V>`/`<H>` tokens against.
 *
 * The desktop distinguishes nvenc/qsv/amf/cpu, but on Android (per Phase-0 ground truth) every
 * GPU branch collapses to MediaCodec, so there are exactly two outcomes per family. An unknown
 * GPU id maps to [SOFTWARE] (the desktop's silent `amf → libx264/libx265` fall-through).
 */
enum class BuilderEncoder { SOFTWARE, HARDWARE }

/** Resolves the `<V>` (H.264) and `<H>` (HEVC) codec tokens. AV1/VP9/ProRes/etc. are fixed. */
object EncoderResolver {
    fun h264(e: BuilderEncoder): String = if (e == BuilderEncoder.HARDWARE) "h264_mediacodec" else "libx264"
    fun hevc(e: BuilderEncoder): String = if (e == BuilderEncoder.HARDWARE) "hevc_mediacodec" else "libx265"
}

/**
 * Byte-exact port of the desktop `buildFfmpegArgs`.
 *
 * Returns the ffmpeg args that sit **between** `-i <input>` and `<output>` — i.e. the caller
 * assembles `ffmpeg -y -i <input> <build(...)> <finalize...> <output>`. Token order is
 * load-bearing (note where `-maxrate`/`-bufsize` precede `-vf`, and `-preset` precedes `-vf`).
 *
 * Trim: `-ss`/`-to` are pushed FIRST (before `-c:v`), matching the desktop's output-seek
 * placement. Values are `floor(fraction * durationSec)` rendered as an integer string.
 *
 * Unknown / `custom-*` ids return **trim-only** args (the desktop's early return); the `default`
 * ffmpeg case is unreachable because all 52 built-in ids have an explicit arm.
 */
object FfmpegArgs {

    fun build(
        presetId: String,
        encoder: BuilderEncoder,
        trimIn: Double = 0.0,
        trimOut: Double = 1.0,
        durationSec: Double = 0.0,
    ): List<String> {
        val out = ArrayList<String>()
        if (trimIn > 0.0) { out += "-ss"; out += floor(trimIn * durationSec).toLong().toString() }
        if (trimOut < 1.0) { out += "-to"; out += floor(trimOut * durationSec).toLong().toString() }

        val v = EncoderResolver.h264(encoder)
        val h = EncoderResolver.hevc(encoder)

        val body: List<String>? = when (presetId) {
            // ── H.264 family (uses <V>) ─────────────────────────────
            "h264-source" -> listOf("-c:v", v, "-crf", "20", "-preset", "medium", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
            "h264-720" -> listOf("-c:v", v, "-crf", "22", "-preset", "medium", "-vf", "scale=-2:720", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart")
            "h264-1080" -> listOf("-c:v", v, "-crf", "20", "-preset", "medium", "-vf", "scale=-2:1080", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
            "h264-1440" -> listOf("-c:v", v, "-crf", "20", "-preset", "medium", "-maxrate", "18M", "-bufsize", "36M", "-vf", "scale=-2:1440", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
            "h264-4k" -> listOf("-c:v", v, "-crf", "20", "-preset", "medium", "-maxrate", "35M", "-bufsize", "70M", "-vf", "scale=-2:2160", "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart")
            // ── HEVC family (uses <H>, always -tag:v hvc1) ───────────
            "h265-source" -> listOf("-c:v", h, "-crf", "23", "-preset", "medium", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-tag:v", "hvc1")
            "h265-720" -> listOf("-c:v", h, "-crf", "25", "-preset", "medium", "-vf", "scale=-2:720", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", "-tag:v", "hvc1")
            "h265-1080" -> listOf("-c:v", h, "-crf", "23", "-preset", "medium", "-vf", "scale=-2:1080", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-tag:v", "hvc1")
            "h265-1440" -> listOf("-c:v", h, "-crf", "23", "-preset", "medium", "-maxrate", "12M", "-bufsize", "24M", "-vf", "scale=-2:1440", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-tag:v", "hvc1")
            "h265-4k" -> listOf("-c:v", h, "-crf", "24", "-preset", "medium", "-maxrate", "22M", "-bufsize", "44M", "-vf", "scale=-2:2160", "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart", "-tag:v", "hvc1")
            // ── AV1 family (always libsvtav1, -preset 6) ─────────────
            "av1-source" -> listOf("-c:v", "libsvtav1", "-crf", "30", "-preset", "6", "-c:a", "libopus", "-b:a", "128k")
            "av1-720" -> listOf("-c:v", "libsvtav1", "-crf", "32", "-preset", "6", "-vf", "scale=-2:720", "-c:a", "libopus", "-b:a", "96k")
            "av1-1080" -> listOf("-c:v", "libsvtav1", "-crf", "30", "-preset", "6", "-vf", "scale=-2:1080", "-c:a", "libopus", "-b:a", "128k")
            "av1-1440" -> listOf("-c:v", "libsvtav1", "-crf", "30", "-preset", "6", "-vf", "scale=-2:1440", "-c:a", "libopus", "-b:a", "160k")
            "av1-4k" -> listOf("-c:v", "libsvtav1", "-crf", "30", "-preset", "6", "-vf", "scale=-2:2160", "-c:a", "libopus", "-b:a", "160k")
            // ── VP9 family (always libvpx-vp9, -row-mt 1) ────────────
            "vp9-source" -> listOf("-c:v", "libvpx-vp9", "-crf", "31", "-b:v", "0", "-row-mt", "1", "-c:a", "libopus", "-b:a", "128k")
            "vp9-720" -> listOf("-c:v", "libvpx-vp9", "-b:v", "2.5M", "-row-mt", "1", "-vf", "scale=-2:720", "-c:a", "libopus", "-b:a", "96k")
            "webm-vp9" -> listOf("-c:v", "libvpx-vp9", "-b:v", "5M", "-row-mt", "1", "-vf", "scale=-2:1080", "-c:a", "libopus", "-b:a", "128k")
            "vp9-1440" -> listOf("-c:v", "libvpx-vp9", "-b:v", "9M", "-row-mt", "1", "-vf", "scale=-2:1440", "-c:a", "libopus", "-b:a", "128k")
            "vp9-4k" -> listOf("-c:v", "libvpx-vp9", "-b:v", "18M", "-row-mt", "1", "-vf", "scale=-2:2160", "-c:a", "libopus", "-b:a", "160k")
            // ── Editing intermediates (no scale; PCM audio) ──────────
            "prores-422" -> listOf("-c:v", "prores_ks", "-profile:v", "2", "-c:a", "pcm_s16le")
            "prores-hq" -> listOf("-c:v", "prores_ks", "-profile:v", "3", "-c:a", "pcm_s16le")
            "prores-4444" -> listOf("-c:v", "prores_ks", "-profile:v", "4", "-pix_fmt", "yuva444p10le", "-c:a", "pcm_s16le")
            "dnxhr-hq" -> listOf("-c:v", "dnxhd", "-profile:v", "dnxhr_hq", "-c:a", "pcm_s16le")
            "dnxhr-sq" -> listOf("-c:v", "dnxhd", "-profile:v", "dnxhr_sq", "-c:a", "pcm_s16le")
            "dnxhr-444" -> listOf("-c:v", "dnxhd", "-profile:v", "dnxhr_444", "-pix_fmt", "yuv444p10le", "-c:a", "pcm_s16le")
            // ── Social ──────────────────────────────────────────────
            "yt-source" -> listOf("-c:v", v, "-crf", "18", "-preset", "slow", "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart")
            "yt-720" -> listOf("-c:v", v, "-b:v", "5M", "-preset", "slow", "-vf", "scale=-2:720", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
            "yt-1080" -> listOf("-c:v", v, "-b:v", "12M", "-preset", "slow", "-vf", "scale=-2:1080", "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart")
            "yt-1440" -> listOf("-c:v", v, "-b:v", "24M", "-preset", "slow", "-vf", "scale=-2:1440", "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart")
            "yt-4k" -> listOf("-c:v", v, "-b:v", "45M", "-preset", "slow", "-vf", "scale=-2:2160", "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart")
            "ig-vert" -> listOf("-c:v", v, "-b:v", "12M", "-vf", "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
            "ig-square" -> listOf("-c:v", v, "-b:v", "8M", "-vf", "scale=1080:1080:force_original_aspect_ratio=decrease,pad=1080:1080:(ow-iw)/2:(oh-ih)/2", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
            "twitter" -> listOf("-c:v", v, "-b:v", "25M", "-preset", "medium", "-vf", "scale=-2:1080", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
            "discord-25" -> listOf("-c:v", v, "-fs", "25M", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart")
            "discord-8" -> listOf("-c:v", v, "-fs", "8M", "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart")
            // ── Audio (lead with -vn, no video codec) ────────────────
            "mp3-320" -> listOf("-vn", "-c:a", "libmp3lame", "-b:a", "320k")
            "aac-256" -> listOf("-vn", "-c:a", "aac", "-b:a", "256k")
            "opus-160" -> listOf("-vn", "-c:a", "libopus", "-b:a", "160k")
            "flac" -> listOf("-vn", "-c:a", "flac")
            "wav" -> listOf("-vn", "-c:a", "pcm_s16le")
            // ── Devices ─────────────────────────────────────────────
            "apple-iphone" -> listOf("-c:v", v, "-vf", "scale=-2:1080", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
            "apple-ipad" -> listOf("-c:v", h, "-vf", "scale=-2:1080", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-tag:v", "hvc1")
            "apple-tv" -> listOf("-c:v", h, "-pix_fmt", "yuv420p10le", "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart", "-tag:v", "hvc1")
            "android-mobile" -> listOf("-c:v", v, "-crf", "23", "-preset", "medium", "-vf", "scale=-2:720", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", "-profile:v", "main", "-level", "4.0")
            "android-tablet" -> listOf("-c:v", v, "-crf", "21", "-preset", "medium", "-vf", "scale=-2:1080", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-profile:v", "high", "-level", "4.1")
            "roku-fire" -> listOf("-c:v", h, "-crf", "24", "-preset", "medium", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart", "-tag:v", "hvc1", "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709")
            "plex-direct" -> listOf("-c:v", v, "-crf", "20", "-preset", "medium", "-profile:v", "high", "-level", "4.2", "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart")
            // ── Legacy (fixed mpeg2video; -f dvd at end) ─────────────
            "dvd-ntsc" -> listOf("-c:v", "mpeg2video", "-vf", "scale=720:480", "-r", "29.97", "-b:v", "6M", "-c:a", "ac3", "-b:a", "192k", "-f", "dvd")
            "dvd-pal" -> listOf("-c:v", "mpeg2video", "-vf", "scale=720:576", "-r", "25", "-b:v", "6M", "-c:a", "ac3", "-b:a", "192k", "-f", "dvd")
            // ── Image (gif has NO -c:v; webp-anim uses -vcodec) ──────
            "gif" -> listOf("-vf", "fps=12,scale=480:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse", "-loop", "0")
            "webp-anim" -> listOf("-vcodec", "libwebp", "-vf", "fps=24", "-quality", "80", "-loop", "0")
            // ── custom-* / unknown → trim-only (desktop early return) ─
            else -> null
        }

        if (body != null) out += body
        return out
    }
}
