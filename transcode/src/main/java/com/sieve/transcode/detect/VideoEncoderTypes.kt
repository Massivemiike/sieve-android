package com.sieve.transcode.detect

/** MediaCodec MIME types for the two hardware-encodable families on Android. */
object VideoMime {
    const val H264 = "video/avc"
    const val HEVC = "video/hevc"
}

/** One hardware video encoder reported by the platform probe. */
data class HardwareEncoderInfo(
    val mime: String,
    val codecName: String,
    val isHardwareAccelerated: Boolean,
)

/**
 * Seam over the two platform facts the detector needs. The real implementation lives in the
 * Android layer (MediaCodecList + `ffmpeg -encoders`); tests drive a fake.
 */
interface VideoEncoderProbe {
    fun hardwareVideoEncoders(): List<HardwareEncoderInfo>
    /** Lowercased encoder names from `ffmpeg -encoders` (e.g. `h264_mediacodec`). */
    fun ffmpegEncoderNames(): Set<String>
}

enum class EncoderKind { HARDWARE, CPU }

/**
 * A selectable encoder in the UI. [active] is a UI flag (the recommended/highlighted option) and
 * is distinct from [DetectionResult.selected] (the currently-chosen id).
 */
data class EncoderOption(
    val id: String,
    val label: String,
    val dev: String,
    val kind: EncoderKind,
    val active: Boolean,
    val perf: String,
)

data class DetectionResult(val encoders: List<EncoderOption>, val selected: String)
