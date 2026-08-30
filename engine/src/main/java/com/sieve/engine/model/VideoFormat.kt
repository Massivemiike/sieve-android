package com.sieve.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A single yt-dlp format. `filesize`/`filesize_approx` are Long (4K exceeds Int); "none" is a sentinel, not null. */
@Serializable
data class VideoFormat(
    @SerialName("format_id") val formatId: String,
    @SerialName("format_note") val formatNote: String? = null,
    val ext: String = "",
    val resolution: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val filesize: Long? = null,
    @SerialName("filesize_approx") val filesizeApprox: Long? = null,
    val tbr: Double? = null,
    val abr: Double? = null,
    val vbr: Double? = null,
    @SerialName("dynamic_range") val dynamicRange: String? = null,
    val protocol: String? = null,
    val url: String? = null,
) {
    val hasVideo get() = vcodec != null && vcodec != "none"
    val hasAudio get() = acodec != null && acodec != "none"
    val isAudioOnly get() = hasAudio && !hasVideo
    val isStoryboard get() = formatNote == "storyboard"
    val isHdr get() = dynamicRange != null && dynamicRange != "SDR"
    val bestSizeBytes: Long? get() = filesize?.takeIf { it > 0 } ?: filesizeApprox?.takeIf { it > 0 }
    val qualityLabel: String get() = formatNote ?: height?.let { "${it}p" } ?: abr?.let { "${it.toInt()}k" } ?: "?"
    val videoCodecFamily: String? get() = vcodec?.takeIf { it != "none" }?.substringBefore('.')
    val audioCodecFamily: String? get() = acodec?.takeIf { it != "none" }?.substringBefore('.')
}
