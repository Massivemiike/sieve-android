package com.sieve.app.ui.download

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.ui.graphics.vector.ImageVector

/** A one-tap download preset. Ported from the desktop NewDownload.tsx DOWNLOAD_PRESETS (MP4/MP3/Opus presets differ, see below). */
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
    /** What a fresh install starts on (the first preset). */
    const val DEFAULT_ID = "best-video"

    /**
     * The format selector of the 1080p / 720p MP4 presets (the size cap lives in [mp4Args]). Alternatives, first match wins:
     *  1. known H.264 video (avc1, or a literal "h264") + separate audio;
     *  2. a known-H.264 file that already carries its audio;
     *  3. Facebook's progressive `sd`: yt-dlp cannot see its codec, but it is H.264 (probed), while Facebook's
     *     `hd` is VP9 and its DASH video is VP9/AV1, so only `sd` can keep the H.264 promise there;
     *  4. any video + audio, and 5. any file: only when the site offers no H.264 at all.
     * Not filters on `height`: a vertical video's size is its SHORT side (a 480x848 clip is "480p"), and a `height<=`
     * filter dropped it, and every format whose height yt-dlp does not know.
     */
    internal const val MP4_FORMAT =
        "bv[vcodec~='^(avc|h264)']+ba/b[vcodec~='^(avc|h264)']/b[format_id=sd][ext=mp4]/bv*+ba/b"

    /**
     * Extra yt-dlp args of an MP4 preset capped at [shortSide]. `-S res:N` ranks by the SMALLEST dimension, the best one at
     * or under N first (never a hard cut: if nothing is that small the smallest above it is taken, where the old chain
     * took `best`); H.264, AAC, then mp4/m4a break ties. `--merge-output-format mp4` keeps even the no-H.264 fallback
     * (e.g. VP9 + Opus, which would merge into .mkv) an .mp4. Nothing is re-encoded.
     */
    internal fun mp4Args(shortSide: Int): List<String> =
        listOf("-S", "res:$shortSide,vcodec:h264,acodec:aac,ext:mp4:m4a", "--merge-output-format", "mp4")

    val ALL: List<DownloadPreset> = listOf(
        DownloadPreset(
            "best-video", "Best video + audio", "Highest quality available",
            "bestvideo*+bestaudio/best", badge = "Recommended",
        ),
        // H.264 first (see MP4_FORMAT). Divergence from the desktop strings, which still have the old bugs: [ext=mp4] alone
        // matched VP9/AV1-in-MP4, and [height<=N] mis-sized vertical video and skipped formats with an unknown height.
        DownloadPreset(
            "best-1080", "1080p MP4", "H.264 up to 1080p, widely compatible",
            MP4_FORMAT, extraArgs = mp4Args(1080),
        ),
        DownloadPreset(
            "best-720", "720p MP4", "Smaller file, good quality",
            MP4_FORMAT, extraArgs = mp4Args(720),
        ),
        DownloadPreset(
            "best-4k", "4K / Best resolution", "Up to 4K if available",
            "bestvideo[height<=2160]+bestaudio/best",
        ),
        DownloadPreset(
            "audio-best", "Best audio only", "Extract audio, best quality",
            "bestaudio/best", extraArgs = listOf("-x"), audioOnly = true, icon = Icons.Filled.MusicNote,
        ),
        // 320K = a constant 320 kbps (libmp3lame -b:a 320k, as the Windows mp3-320 transcode). `--audio-quality 0` was
        // LAME's V0 VBR, about 245 kbps. A source that already is MP3 is left as it is: yt-dlp never re-encodes it.
        DownloadPreset(
            "audio-mp3", "MP3 320kbps", "Extract audio as MP3",
            "bestaudio/best", extraArgs = listOf("-x", "--audio-format", "mp3", "--audio-quality", "320K"),
            audioOnly = true, icon = Icons.Filled.MusicNote,
        ),
        // Always an .opus: a source that already is Opus (YouTube 251) is copied untouched, anything else (SoundCloud and most
        // other sites only have AAC/MP3) is encoded at 128 kbps: about what YouTube's own Opus is, and plenty for the 60-160 kbps
        // lossy sources these sites serve (a higher rate only makes the file bigger; the Windows opus-160 transcode is a different tool).
        // (`-x` alone kept the source codec: SoundCloud saved an .m4a.)
        DownloadPreset(
            "audio-opus", "Opus (smallest)", "High quality, tiny file",
            "bestaudio/best", extraArgs = listOf("-x", "--audio-format", "opus", "--audio-quality", "128K"),
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
