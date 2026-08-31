package com.sieve.app.ui.download

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.ui.graphics.vector.ImageVector

/** A one-tap download preset. Ported verbatim from the desktop NewDownload.tsx DOWNLOAD_PRESETS. */
data class DownloadPreset(
    val id: String,
    val label: String,
    val desc: String,
    val format: String,
    val extraArgs: List<String> = emptyList(),
    val audioOnly: Boolean = false,
    val icon: ImageVector = Icons.Filled.Movie,
    val badge: String? = null,
)

object DownloadPresets {
    val ALL: List<DownloadPreset> = listOf(
        DownloadPreset(
            "best-video", "Best video + audio", "Highest quality available",
            "bestvideo*+bestaudio/best", badge = "Recommended",
        ),
        DownloadPreset(
            "best-1080", "1080p MP4", "H.264 1080p, widely compatible",
            "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best",
        ),
        DownloadPreset(
            "best-720", "720p MP4", "Smaller file, good quality",
            "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=720]+bestaudio/best[height<=720]/best",
        ),
        DownloadPreset(
            "best-4k", "4K / Best resolution", "Up to 4K if available",
            "bestvideo[height<=2160]+bestaudio/best",
        ),
        DownloadPreset(
            "audio-best", "Best audio only", "Extract audio, best quality",
            "bestaudio/best", extraArgs = listOf("-x"), audioOnly = true, icon = Icons.Filled.MusicNote,
        ),
        DownloadPreset(
            "audio-mp3", "MP3 320kbps", "Extract audio as MP3",
            "bestaudio/best", extraArgs = listOf("-x", "--audio-format", "mp3", "--audio-quality", "0"),
            audioOnly = true, icon = Icons.Filled.MusicNote,
        ),
        DownloadPreset(
            "audio-opus", "Opus (smallest)", "High quality, tiny file",
            "bestaudio[ext=webm]/bestaudio/best", extraArgs = listOf("-x"),
            audioOnly = true, icon = Icons.Filled.MusicNote,
        ),
        DownloadPreset(
            "archive", "Archive (MKV)", "Best quality, all subs & metadata",
            "bestvideo+bestaudio/best",
            extraArgs = listOf("--embed-subs", "--all-subs", "--embed-chapters", "--write-info-json", "--remux-video", "mkv"),
            icon = Icons.Filled.Archive,
        ),
    )

    fun byId(id: String): DownloadPreset = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
