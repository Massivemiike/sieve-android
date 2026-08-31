package com.sieve.transcode.detect

/**
 * Resolves the available encoders via a **double gate**: a hardware family is offered only when the
 * device reports a hardware encoder for it AND ffmpeg exposes the matching `*_mediacodec` name.
 * Never collapse the two conditions — device support without an ffmpeg wrapper is unusable.
 *
 * On any probe failure the detector degrades to CPU-only (never throws). There is no AMD/AMF branch
 * on Android; every hardware family maps through the single `hw-` prefix.
 */
class EncoderDetector(
    private val probe: VideoEncoderProbe,
    private val coreCount: () -> Int,
) {

    fun detect(savedEncoderId: String?): DetectionResult {
        val cores = coreCount()
        val cpu = EncoderOption("cpu", "CPU (libx264/libx265)", "$cores cores", EncoderKind.CPU, active = false, perf = "1x")

        val hwList: List<EncoderOption> = try {
            val hw = probe.hardwareVideoEncoders()
            val ff = probe.ffmpegEncoderNames()
            val hasHwH264 = hw.any { it.mime == VideoMime.H264 } && "h264_mediacodec" in ff
            val hasHwHevc = hw.any { it.mime == VideoMime.HEVC } && "hevc_mediacodec" in ff
            buildList {
                if (hasHwH264) add(EncoderOption("hw-h264", "Hardware H.264", "MediaCodec", EncoderKind.HARDWARE, active = false, perf = "hw"))
                if (hasHwHevc) add(EncoderOption("hw-hevc", "Hardware HEVC", "MediaCodec", EncoderKind.HARDWARE, active = false, perf = "hw"))
            }
        } catch (e: Throwable) {
            return DetectionResult(listOf(cpu.copy(active = true)), "cpu")
        }

        // First HW option is the recommended (active) one; CPU is active only when no HW exists.
        val marked = hwList.mapIndexed { i, e -> e.copy(active = i == 0) }
        val encoders = marked + cpu.copy(active = marked.isEmpty())

        val selected = if (savedEncoderId != null && encoders.any { it.id == savedEncoderId }) savedEncoderId
        else encoders.first().id
        return DetectionResult(encoders, selected)
    }
}

/** Bridges a resolved encoder id to the codec tokens the arg-builder needs. */
object EncoderMapper {
    fun videoEncoder(id: String): String = if (id.startsWith("hw")) "h264_mediacodec" else "libx264"
    fun hevcEncoder(id: String): String = if (id.startsWith("hw")) "hevc_mediacodec" else "libx265"
}
