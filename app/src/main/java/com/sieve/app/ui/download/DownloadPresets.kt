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

    private const val H264 = "[vcodec~='^(avc|h264)']"

    /**
     * yt-dlp's regex (searched in the stream URL) for the Facebook `hd` encodes that really are H.264. A Facebook progressive
     * `sd` / `hd` has no codec, size or note for yt-dlp (it prints "unknown"), but the CDN URL ends in `&tag=<encode>`. Probed
     * with ffprobe over eight Facebook videos, old and new: `tag=hd` is H.264 High 1280x720 (an old video), `tag=dash_h264-...`
     * H.264 High 720x1280, whereas `tag=compressed_source` is VP9 (reel 480x848, videos 1080x1080 and 1080x1920) and
     * `tag=av1_compressed_source` is AV1 by its name. So a modern video's `hd` is often a VP9 re-encode while an older one's is
     * the best H.264 there is, and only the tag tells them apart. A tag not listed here (a new one, a renamed one) simply misses
     * this alternative and the chain falls to `sd`: never to a VP9 / AV1 `hd`.
     */
    internal const val FB_H264_HD_TAG = "[?&]tag=(hd|dash_h264[a-z0-9_-]*)(&|\$)"

    /**
     * The format selector of the 1080p / 720p MP4 presets (the size cap lives in [mp4Args]). Alternatives, first match wins:
     *  1. known H.264 video (avc1, or a literal "h264") + separate audio;
     *  2. a known-H.264 file that already carries its audio;
     *  3. Facebook's progressive `hd`, but only when the CDN's own label says H.264 ([FB_H264_HD_TAG]);
     *  4. Facebook's progressive `sd`: H.264 on every video probed (`tag=sd` / `sve_sd`, Constrained Baseline or Main, a
     *     few hundred pixels), so it is the H.264 floor there when `hd` is a VP9 / AV1 re-encode (and Facebook's DASH video
     *     is VP9 / AV1 on modern videos);
     *  5. any video + audio, and 6. any file: only when the site offers no H.264 at all.
     * Not filters on `height`: a vertical video's size is its SHORT side (a 480x848 clip is "480p"), and a `height<=`
     * filter dropped it, and every format whose height yt-dlp does not know. Edge: when the only H.264 is above the cap (a
     * lone avc 1080p beside a VP9 720p), [mp4Args]'s sort takes that smallest H.264 over a VP9 under the cap, so the 720p
     * preset's "smaller file" is not guaranteed there.
     */
    internal const val MP4_FORMAT =
        "bv$H264+ba/b$H264/b[format_id=hd][ext=mp4][url~='$FB_H264_HD_TAG']/b[format_id=sd][ext=mp4]/bv*+ba/b"

    /**
     * Extra yt-dlp args of an MP4 preset capped at [shortSide]. `-S res:N` ranks by the SMALLEST dimension, the best one at
     * or under N first (never a hard cut: if nothing is that small the smallest above it is taken, where the old chain
     * took `best`); H.264, AAC, then mp4/m4a break ties. `--merge-output-format mp4` makes a fallback that is a MERGE of two
     * streams (e.g. VP9 + Opus, which would merge into .mkv) an .mp4. It does not touch a single-file source, which keeps its
     * own container (a lone .webm / .mkv / .avi on a rare host with no H.264): `--remux-video mp4` would change that but makes
     * the whole download fail when the codecs cannot go into an mp4 (Vorbis), so it is deliberately not used. Nothing is
     * re-encoded.
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
        // 320K = a constant 320 kbps (libmp3lame -b:a 320k, as the Windows mp3-320 transcode); yt-dlp passes a quality above 10 as
        // `-b:a <n>k`. `--audio-quality 0` was LAME's V0 VBR, about 245 kbps. Every source that is not MP3 is encoded to 320 CBR (what
        // bestaudio picks on every site tried). A source that already is MP3 (SoundCloud's 128k http_mp3 when nothing outranks it)
        // is kept as it is: yt-dlp never re-encodes it, and re-encoding an MP3 up to 320 would only make it bigger, not better.
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
