package com.sieve.transcode.runner.android

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever

/**
 * What [SourceProbe] could read from a source. [mime]/[width]/[height] describe the first video
 * track (`mime` is empty and the dimensions are 0 for an audio-only source); [audioSampleRate] is
 * the first audio track's `KEY_SAMPLE_RATE` in Hz, or null when there is no audio track or the
 * rate is unreadable. [durationSec] is the source's length (longest track), null when unreadable.
 */
data class SourceVideoInfo(
    val mime: String,
    val width: Int,
    val height: Int,
    val audioSampleRate: Int? = null,
    val durationSec: Double? = null,
)

/**
 * Cheap source inspection via [MediaExtractor] (no ffprobe — Sieve doesn't ship one). Used at
 * transcode spawn to (a) detect AV1 inputs, which MUST be decoded with `av1_mediacodec` (the
 * bundled ffmpeg has no working software AV1 decoder), (b) feed the source height to
 * [com.sieve.transcode.args.MediaCodecSanitizer]'s bitrate ladder, (c) feed the source audio
 * sample rate to [com.sieve.transcode.args.LoudnormRate] so "Normalize audio" keeps the source rate,
 * and (d) supply the source duration (Windows gets it from ffprobe), without which ffmpeg progress
 * has no denominator and stays indeterminate.
 */
object SourceProbe {

    fun probe(path: String): SourceVideoInfo? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            var videoMime: String? = null
            var width = 0
            var height = 0
            var sawAudio = false
            var sampleRate: Int? = null
            val trackDurationsUs = ArrayList<Long>()
            // Every track is read (no early break): the duration is the longest one.
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                // KEY_DURATION is optional (often missing for MKV/WebM) and microseconds.
                runCatching { f.getLong(MediaFormat.KEY_DURATION) }.getOrNull()?.let { trackDurationsUs += it }
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (videoMime == null && mime.startsWith("video/")) {
                    videoMime = mime
                    width = runCatching { f.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
                    height = runCatching { f.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
                } else if (!sawAudio && mime.startsWith("audio/")) {
                    sawAudio = true
                    sampleRate = runCatching { f.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull()
                }
            }
            val durationSec = pickDurationSec(trackDurationsUs) { retrieverDurationMs(path) }
            when {
                videoMime != null -> SourceVideoInfo(videoMime, width, height, sampleRate, durationSec)
                // Audio-only source: no video info, but the sample rate and duration are still wanted.
                sawAudio -> SourceVideoInfo(mime = "", width = 0, height = 0, audioSampleRate = sampleRate, durationSec = durationSec)
                else -> null
            }
        } finally {
            ex.release()
        }
    }.getOrNull()

    /**
     * The longest positive per-track duration (`KEY_DURATION`, microseconds) in seconds; when no track
     * reports one, the container duration from [retrieverMs] (milliseconds) — evaluated only then.
     * Non-positive or unreadable values count as absent.
     */
    internal fun pickDurationSec(trackDurationsUs: List<Long>, retrieverMs: () -> Long?): Double? {
        val us = trackDurationsUs.filter { it > 0 }.maxOrNull()
        if (us != null) return us / 1_000_000.0
        return retrieverMs()?.takeIf { it > 0 }?.let { it / 1000.0 }
    }

    private fun retrieverDurationMs(path: String): Long? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            r.release()
        }
    }.getOrNull()

    /** Input-side ffmpeg args forced by the source codec; empty when software decode is fine. */
    fun requiredInputArgs(info: SourceVideoInfo?): List<String> = when (info?.mime) {
        // No software AV1 decoder in the bundled ffmpeg — hardware decode is the only path.
        MediaFormat.MIMETYPE_VIDEO_AV1 -> listOf("-c:v", "av1_mediacodec")
        else -> emptyList()
    }
}
