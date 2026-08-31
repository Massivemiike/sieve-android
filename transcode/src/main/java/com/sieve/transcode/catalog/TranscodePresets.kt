package com.sieve.transcode.catalog

import com.sieve.transcode.model.PresetCategory
import com.sieve.transcode.model.PresetCategory.AUDIO
import com.sieve.transcode.model.PresetCategory.DEVICES
import com.sieve.transcode.model.PresetCategory.EDIT
import com.sieve.transcode.model.PresetCategory.IMAGE
import com.sieve.transcode.model.PresetCategory.LEGACY
import com.sieve.transcode.model.PresetCategory.SOCIAL
import com.sieve.transcode.model.PresetCategory.WEB
import com.sieve.transcode.model.TranscodePreset

/**
 * The 52-preset catalog, transcribed byte-for-byte from the desktop transcodeStore.
 *
 * The middle dot in display strings is U+00B7 (`·`) and the multiplication sign is
 * U+00D7 (`×`) — both are load-bearing for display fidelity. Do not substitute ASCII.
 * A `null` badge is the em-dash placeholder in the desktop table.
 */
object TranscodePresets {

    private fun p(id: String, cat: PresetCategory, name: String, sub: String, ext: String, badge: String? = null) =
        TranscodePreset(id, cat, name, sub, ext, badge)

    /** Catalog order matches the desktop rail order (Web → Edit → Social → Audio → Devices → Legacy → Image). */
    val all: List<TranscodePreset> = listOf(
        // ── Web · H.264 ─────────────────────────────────────────────
        p("h264-source", WEB, "H.264 · Source", "mp4 · CRF 20 · keep res", "mp4", "Preserve 4K"),
        p("h264-720", WEB, "H.264 · 720p", "mp4 · 4 Mbps · CRF 22", "mp4"),
        p("h264-1080", WEB, "H.264 · 1080p", "mp4 · 8 Mbps · CRF 20", "mp4", "Most compatible"),
        p("h264-1440", WEB, "H.264 · 1440p", "mp4 · 18 Mbps · CRF 20", "mp4"),
        p("h264-4k", WEB, "H.264 · 4K (2160p)", "mp4 · 35 Mbps · CRF 20", "mp4"),
        // ── Web · HEVC ──────────────────────────────────────────────
        p("h265-source", WEB, "HEVC · Source", "mp4 · CRF 23 · keep res", "mp4", "Preserve 4K"),
        p("h265-720", WEB, "HEVC · 720p", "mp4 · CRF 25", "mp4"),
        p("h265-1080", WEB, "H.265 / HEVC · 1080p", "mp4 · 5 Mbps · CRF 23", "mp4", "Smaller files"),
        p("h265-1440", WEB, "HEVC · 1440p", "mp4 · 12 Mbps · CRF 23", "mp4"),
        p("h265-4k", WEB, "HEVC · 4K (2160p)", "mp4 · 22 Mbps · CRF 24", "mp4"),
        // ── Web · AV1 ───────────────────────────────────────────────
        p("av1-source", WEB, "AV1 · Source", "webm · CRF 30 · keep res", "webm", "Preserve 4K"),
        p("av1-720", WEB, "AV1 · 720p", "webm · CRF 32", "webm"),
        p("av1-1080", WEB, "AV1 · 1080p", "webm · 4 Mbps · CRF 30", "webm", "Modern"),
        p("av1-1440", WEB, "AV1 · 1440p", "webm · 8 Mbps · CRF 30", "webm"),
        p("av1-4k", WEB, "AV1 · 4K (2160p)", "webm · 18 Mbps · CRF 30", "webm"),
        // ── Web · VP9 ───────────────────────────────────────────────
        p("vp9-source", WEB, "VP9 · Source", "webm · keep res · CRF 31", "webm", "Preserve 4K"),
        p("vp9-720", WEB, "VP9 · 720p", "webm · 2.5 Mbps", "webm"),
        p("webm-vp9", WEB, "VP9 · 1080p", "webm · 5 Mbps", "webm"),
        p("vp9-1440", WEB, "VP9 · 1440p", "webm · 9 Mbps", "webm"),
        p("vp9-4k", WEB, "VP9 · 4K (2160p)", "webm · 18 Mbps", "webm"),
        // ── Edit ────────────────────────────────────────────────────
        p("prores-422", EDIT, "ProRes 422", "mov · keep res · 147 Mbps@1080p", "mov", "Editing"),
        p("prores-hq", EDIT, "ProRes 422 HQ", "mov · keep res · 220 Mbps@1080p", "mov"),
        p("prores-4444", EDIT, "ProRes 4444", "mov · keep res · alpha-capable", "mov"),
        p("dnxhr-hq", EDIT, "DNxHR HQ", "mxf · keep res · 145 Mbps", "mxf"),
        p("dnxhr-sq", EDIT, "DNxHR SQ", "mxf · keep res · 100 Mbps", "mxf"),
        p("dnxhr-444", EDIT, "DNxHR 444", "mxf · keep res · 12-bit", "mxf"),
        // ── Social ──────────────────────────────────────────────────
        p("yt-source", SOCIAL, "YouTube · Source", "mp4 · keep res · CRF 18", "mp4", "Best quality"),
        p("yt-720", SOCIAL, "YouTube · 720p", "mp4 · 5 Mbps · 2-pass", "mp4"),
        p("yt-1080", SOCIAL, "YouTube · 1080p", "mp4 · 12 Mbps · 2-pass", "mp4", "Upload-ready"),
        p("yt-1440", SOCIAL, "YouTube · 1440p", "mp4 · 24 Mbps · 2-pass", "mp4"),
        p("yt-4k", SOCIAL, "YouTube · 4K", "mp4 · 45 Mbps · 2-pass", "mp4"),
        p("ig-vert", SOCIAL, "Instagram / TikTok", "1080×1920 · 12 Mbps", "mp4"),
        p("ig-square", SOCIAL, "Instagram · Square", "1080×1080 · 8 Mbps", "mp4"),
        p("twitter", SOCIAL, "Twitter / X", "mp4 · 1080p · 25 Mbps", "mp4"),
        p("discord-25", SOCIAL, "Discord · 25 MB", "mp4 · auto-bitrate cap", "mp4"),
        p("discord-8", SOCIAL, "Discord · 8 MB", "mp4 · low-bitrate fit", "mp4"),
        // ── Audio ───────────────────────────────────────────────────
        p("mp3-320", AUDIO, "MP3 · 320 kbps", "mp3 · CBR", "mp3"),
        p("aac-256", AUDIO, "AAC · 256 kbps", "m4a · LC", "m4a"),
        p("opus-160", AUDIO, "Opus · 160 kbps", "opus · best-quality", "opus", "Smallest"),
        p("flac", AUDIO, "FLAC", "flac · lossless", "flac"),
        p("wav", AUDIO, "WAV · 16-bit PCM", "wav · uncompressed", "wav"),
        // ── Devices ─────────────────────────────────────────────────
        p("apple-iphone", DEVICES, "Apple · iPhone", "H.264 mp4 · 1080p", "mp4"),
        p("apple-ipad", DEVICES, "Apple · iPad", "HEVC mp4 · 1080p", "mp4"),
        p("apple-tv", DEVICES, "Apple TV · 4K HDR", "HEVC mp4 · 4K · 10-bit", "mp4"),
        p("android-mobile", DEVICES, "Android · Mobile", "H.264 mp4 · 720p · small", "mp4"),
        p("android-tablet", DEVICES, "Android · Tablet", "H.264 mp4 · 1080p", "mp4"),
        p("roku-fire", DEVICES, "Roku / Fire TV", "HEVC mp4 · 4K · BT.709", "mp4"),
        p("plex-direct", DEVICES, "Plex · direct-play", "mp4 · keep res · H.264 high", "mp4"),
        // ── Legacy ──────────────────────────────────────────────────
        p("dvd-ntsc", LEGACY, "DVD · NTSC", "mpeg2 · 720×480 · 29.97", "mpg"),
        p("dvd-pal", LEGACY, "DVD · PAL", "mpeg2 · 720×576 · 25", "mpg"),
        // ── Image ───────────────────────────────────────────────────
        p("gif", IMAGE, "GIF · 12 fps", "gif · palette-optimized", "gif"),
        p("webp-anim", IMAGE, "Animated WebP", "webp · 24 fps · q 80", "webp"),
    )

    /** O(1) lookup by id; `null` for unknown/custom ids. */
    val byId: Map<String, TranscodePreset> = all.associateBy { it.id }

    fun find(id: String): TranscodePreset? = byId[id]
}
