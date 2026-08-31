package com.sieve.storage.naming

import android.webkit.MimeTypeMap

object MimeMapper {

    // Deterministic table for the yt-dlp/ffmpeg outputs we produce. Covers what the framework
    // MimeTypeMap misses on some API levels AND the common types (so behavior doesn't depend on the
    // device map). The framework map is only a fallback for the long tail.
    private val OVERRIDES = mapOf(
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "m4a" to "audio/mp4",
        "mp3" to "audio/mpeg",
        "opus" to "audio/ogg",
        "ogg" to "audio/ogg",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "vtt" to "text/vtt",
        "srt" to "application/x-subrip",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
        "json" to "application/json",
    )

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun mimeOf(name: String): String {
        val ext = extensionOf(name)
        if (ext.isEmpty()) return "application/octet-stream"
        OVERRIDES[ext]?.let { return it }
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return "application/octet-stream"
    }
}
