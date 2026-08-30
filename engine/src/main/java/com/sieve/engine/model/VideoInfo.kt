package com.sieve.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Result of `yt-dlp -J`. `cookieFallback` is engine-internal (@Transient — never deserialized). */
@Serializable
data class VideoInfo(
    val id: String? = null,
    val title: String? = null,
    val uploader: String? = null,
    val channel: String? = null,
    val duration: Double? = null,
    val thumbnail: String? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null,
    val extractor: String? = null,
    val formats: List<VideoFormat> = emptyList(),
    val description: String? = null,
    @SerialName("_type") val type: String? = null,
    val entries: List<VideoInfo?> = emptyList(),
    @SerialName("playlist_count") val playlistCount: Int? = null,
    val chapters: List<VideoChapter> = emptyList(),
    @Transient val cookieFallback: Boolean = false,
) {
    val isPlaylist: Boolean get() = type == "playlist"
    val displayChannel: String get() = uploader?.takeIf { it.isNotBlank() } ?: channel.orEmpty()
    fun targetUrl(original: String): String = webpageUrl?.takeIf { it.isNotBlank() } ?: original
}
