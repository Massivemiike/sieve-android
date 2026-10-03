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

    /** The short side of Facebook's progressive `hd` whose CDN tag says H.264 (see [FB_H264_720P_HD_TAG]): a 720p encode. */
    private const val FB_HD_SHORT_SIDE = 720

    /**
     * yt-dlp's regex (searched in the stream URL) for the Facebook progressive `hd` encodes that are H.264 at 720p. A Facebook
     * `sd` / `hd` has no codec, size or note for yt-dlp (it prints "unknown"), but the CDN URL ends in `&tag=<encode>`. Probed with
     * ffprobe over every Facebook video that offered an `hd`: `tag=hd` is H.264 High 1280x720 (an old video) and
     * `tag=dash_h264-basic-gen2_720p` is H.264 High 720x1280, whereas `tag=compressed_source` is VP9 (480x848, 1080x1080,
     * 1080x1920) and `tag=av1_compressed_source` is AV1 (1920x1080). Only a tag that proves both H.264 AND 720p matches, so that
     * the selector can rank this `hd` by size against the DASH formats yt-dlp does see; any other tag (a new one, a renamed one,
     * a `dash_h264...1080p` nobody has probed) misses and the `hd` is just an unknown-size file (see [mp4Format]).
     */
    internal const val FB_H264_720P_HD_TAG = "[?&]tag=(hd|dash_h264[a-z0-9_-]*_720p)(&|\$)"

    private const val FB_H264_720P_HD = "b[format_id=hd][ext=mp4][url~='$FB_H264_720P_HD_TAG']"

    /**
     * The format selector of the 1080p / 720p MP4 presets: the best RESOLUTION available whose short side is at most [shortSide]
     * (a vertical 480x848 clip is "480p"), and H.264 only as the tie-break between formats of the SAME resolution (the owner's
     * "keep resolution" decision of 2026-10-02). The ranking itself is yt-dlp's `-S` sort ([mp4Args]: `res` first, then H.264,
     * AAC, ...), which is why this selector has no codec filter: the first RC put "known H.264" in front ("H.264 first, anything
     * last") and so took a lower-resolution H.264 over a higher-resolution VP9 / AV1 (a Facebook reel's 362x640 `sd` over its
     * 480x848 VP9; YouTube Shorts' 608x1080 H.264 over the 720x1280 VP9). Alternatives, first match wins:
     *  1-2. [only above 720] a video with a KNOWN size whose short side is in (720, [shortSide]] + audio. Not a different choice
     *     from 4 on any site but Facebook: when such a format exists the sort takes the best one there anyway. It is the guard of
     *     alternative 3, which must not win over a bigger format.
     *      * 1 is `width` in (720, N] and `height` > N (a portrait or tall format), 2 is `height` in (720, N] and `width` > 720
     *        (a landscape or square one): disjoint, and together exactly "short side in (720, N]" (the app's tests check the
     *        filters against every size). A bare `[height<=N]` would be the LONG side of a vertical video and drop it.
     *  3. Facebook's progressive `hd`, only when the CDN tag proves it is H.264 at 720p ([FB_H264_720P_HD_TAG]): yt-dlp cannot
     *     see that size, so it is ranked as the 720p H.264 it is. Beside a VP9 / AV1 DASH video of the same 720p it wins (H.264
     *     breaks the tie), a bigger DASH rung (alternatives 1-2) beats it, a smaller one loses to it.
     *  4. any video + audio, or a single file (`bv*+ba`, then `b`): the sort decides. A format of unknown size ranks under every
     *     known one inside the cap, so where yt-dlp knows the sizes (YouTube, the DASH ladders of Facebook / Instagram, Vimeo and X's HLS) they
     *     are compared by real size; where the unknown ones are all there is (LinkedIn's 0/1/2, an older Facebook video's
     *     `sd` / `hd`) the extractor's own `quality` / bitrate picks (`hd` over `sd`, LinkedIn's biggest).
     * Facebook with DASH formats gives the best DASH rung within the cap (a VP9 / AV1 480x848, 720x1280, 1080x1920), never the
     * H.264 `sd`. Where a video has only `sd` / `hd` and `hd` is a `compressed_source`, `hd` is taken (a VP9 / AV1 at the upload's
     * own size, as 1.0.3 did) even if that is above the cap: yt-dlp cannot see how big it is.
     */
    internal fun mp4Format(shortSide: Int): String {
        require(shortSide >= FB_HD_SHORT_SIDE) { "the Facebook hd rule is a 720p rule: $shortSide" }
        val tiers = ArrayList<String>()
        if (shortSide > FB_HD_SHORT_SIDE) {
            tiers += "bv*[width>$FB_HD_SHORT_SIDE][width<=$shortSide][height>$shortSide]+ba"
            tiers += "bv*[height>$FB_HD_SHORT_SIDE][height<=$shortSide][width>$FB_HD_SHORT_SIDE]+ba"
        }
        tiers += FB_H264_720P_HD
        tiers += "bv*+ba"
        tiers += "b"
        return tiers.joinToString("/")
    }

    /**
     * Extra yt-dlp args of an MP4 preset capped at [shortSide]. `-S res:N,...` ranks by the SMALLEST dimension, the best one at
     * or under N first (never a hard cut: if nothing is that small the smallest above it is taken). Only among formats of that
     * same resolution do the rest of the keys decide, in this order: H.264 (`vcodec:h264`; after it yt-dlp's own order: VP8 / MPEG-4,
     * a file whose codec yt-dlp cannot see, then the codec nearest H.264: HEVC, VP9, AV1), AAC audio, https before HLS (so a VP9
     * `.webm` over https beats the same VP9 as an HLS `.mp4`, which `ext` alone would have preferred), then mp4 / m4a.
     * `--merge-output-format mp4` makes every MERGE of two streams (a VP9 / AV1 video + AAC) an .mp4. It does not touch a
     * single-file source, which keeps its own container (a lone muxed .webm / .mkv on a rare host): `--remux-video mp4` would
     * change that but makes the whole download fail when the codecs cannot go into an mp4 (Vorbis), so it is deliberately not
     * used. Nothing is re-encoded.
     */
    internal fun mp4Args(shortSide: Int): List<String> =
        listOf("-S", "res:$shortSide,vcodec:h264,acodec:aac,proto,ext:mp4:m4a", "--merge-output-format", "mp4")

    val ALL: List<DownloadPreset> = listOf(
        DownloadPreset(
            "best-video", "Best video + audio", "Highest quality available",
            "bestvideo*+bestaudio/best", badge = "Recommended",
        ),
        // Resolution first, H.264 only breaks ties (see mp4Format). Divergence from the desktop strings, which still have the old
        // bugs: [ext=mp4] alone matched VP9/AV1-in-MP4 at random, and [height<=N] mis-sized vertical video and skipped formats
        // with an unknown height. The descriptions are short on purpose: the card is half a phone wide and shows one line.
        DownloadPreset(
            "best-1080", "1080p MP4", "Sharpest up to 1080p",
            mp4Format(1080), extraArgs = mp4Args(1080),
        ),
        DownloadPreset(
            "best-720", "720p MP4", "Up to 720p, smaller file",
            mp4Format(720), extraArgs = mp4Args(720),
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
