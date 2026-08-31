package com.sieve.transcode.detect.android

import android.media.MediaCodecList
import android.os.Build
import com.sieve.transcode.detect.HardwareEncoderInfo
import com.sieve.transcode.detect.VideoEncoderProbe
import com.sieve.transcode.detect.VideoMime

/**
 * Real [VideoEncoderProbe]: enumerates hardware H.264/HEVC encoders via [MediaCodecList] and reports
 * which of the four relevant ffmpeg encoder names appear in a captured `ffmpeg -encoders` dump.
 *
 * On API < 29 (no `isHardwareAccelerated`) it heuristically treats non-`OMX.google.`/non-`c2.android.`
 * codecs as hardware. Pass the `ffmpeg -encoders` stdout captured once at startup as [ffmpegEncodersStdout].
 */
class AndroidVideoEncoderProbe(private val ffmpegEncodersStdout: String) : VideoEncoderProbe {

    override fun hardwareVideoEncoders(): List<HardwareEncoderInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { info ->
                info.supportedTypes
                    .filter { it.equals(VideoMime.H264, true) || it.equals(VideoMime.HEVC, true) }
                    .map { mime ->
                        HardwareEncoderInfo(
                            mime = mime.lowercase(),
                            codecName = info.name,
                            isHardwareAccelerated = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                            else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android."),
                        )
                    }
            }
            .filter { it.isHardwareAccelerated }

    override fun ffmpegEncoderNames(): Set<String> =
        setOf("h264_mediacodec", "hevc_mediacodec", "libx264", "libx265")
            .filter { it in ffmpegEncodersStdout.lowercase() }
            .toSet()
}
