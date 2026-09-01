package com.sieve.transcode.runner.android

import android.media.MediaExtractor
import android.media.MediaFormat

/** Codec MIME + dimensions of a source's first video track, or null when unreadable. */
data class SourceVideoInfo(val mime: String, val width: Int, val height: Int)

/**
 * Cheap source inspection via [MediaExtractor] (no ffprobe — Sieve doesn't ship one). Used at
 * transcode spawn to (a) detect AV1 inputs, which MUST be decoded with `av1_mediacodec` (the
 * bundled ffmpeg has no working software AV1 decoder), and (b) feed the source height to
 * [com.sieve.transcode.args.MediaCodecSanitizer]'s bitrate ladder.
 */
object SourceProbe {

    fun probe(path: String): SourceVideoInfo? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    return@runCatching SourceVideoInfo(
                        mime = mime,
                        width = runCatching { f.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0),
                        height = runCatching { f.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0),
                    )
                }
            }
            null
        } finally {
            ex.release()
        }
    }.getOrNull()

    /** Input-side ffmpeg args forced by the source codec; empty when software decode is fine. */
    fun requiredInputArgs(info: SourceVideoInfo?): List<String> = when (info?.mime) {
        // No software AV1 decoder in the bundled ffmpeg — hardware decode is the only path.
        MediaFormat.MIMETYPE_VIDEO_AV1 -> listOf("-c:v", "av1_mediacodec")
        else -> emptyList()
    }
}
