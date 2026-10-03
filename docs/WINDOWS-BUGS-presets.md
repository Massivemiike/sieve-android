# WINDOWS-BUGS: bugs found in the Windows app while working on Android

A running list of bugs of the Windows app (v0.3.1, commit `8fb32c5`) found while working on the Android app, for the Windows
app's to-do list. This file was created on the `rc/1.0.4-presets` branch for the preset fixes. It is deliberately NOT named
`WINDOWS-BUGS.md`: the longer list of that name on `feat/full-parity` would make a git add/add conflict when the two lines
meet. It uses the same table columns, so when they do meet, append the "Download presets (RC 1.0.4)" section below to
`docs/WINDOWS-BUGS.md` (the `DP-` ids and the marker line stay as they are) and delete this file.

## Rules for appending

* **Append-only, one row per bug**, in the area's table, directly above that table's `<!-- append ... rows above this line -->` marker.
* **Evidence is mandatory**: `WIN:<path>:<lines>` in the Windows repo `yt-dlp-gui`, or the words `owner-reported`.
* Columns: **Id**, **Windows behaviour**, **Evidence**, **Android** (what Android did about it).

## Download presets (RC 1.0.4)

Source: the owner's signed 1.0.4 release test on the phone, then each fix proved against YouTube (landscape and Shorts), LinkedIn,
SoundCloud, Facebook (ten videos), Instagram, Vimeo, X and Dailymotion with yt-dlp 2026.08.19 + ffprobe. The Windows
`DOWNLOAD_PRESETS` array holds the same three bugs (`NewDownload.tsx:118-126`), in the same strings.

The MP4 presets follow the owner's decision of 2026-10-02 ("keep resolution"): **the best RESOLUTION whose short side is at most
N (720 / 1080), in an .mp4; H.264 and AAC only break ties between formats of the same resolution; never a lower-resolution
H.264 over a higher-resolution VP9 / AV1; never a re-encode.**

| Id | Windows behaviour | Evidence | Android |
|---|---|---|---|
| DP-1 | **"1080p MP4" / "720p MP4" ("H.264 1080p, widely compatible") are neither H.264 nor sized by the short side.** The selectors `bestvideo[height<=N][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=N]+bestaudio/best[height<=N]/best` pin the container, not the codec: `[ext=mp4]` also matches VP9 / AV1 in MP4 (a Facebook reel saved VP9 in an .mp4; the owner also reports AV1 on YouTube, OW-1 on `feat/full-parity`). `[height<=N]` measures a portrait video by its long side (the 720p preset gives a 406x720 Short although the 608x1080 stream is inside a 720 short-side cap) and drops every format whose height yt-dlp cannot read (Facebook sd/hd, LinkedIn, Instagram progressive), so those fall through to `/best`. The `/best` tiers can also end in .webm / .mkv (no `--merge-output-format`). | owner-reported; WIN:src/pages/NewDownload.tsx:120-121 | RC 1.0.4: `DownloadPresets.mp4Format(N)` + `mp4Args(N)`. yt-dlp's own sort does the choosing: `-S res:N,vcodec:h264,acodec:aac,proto,ext:mp4:m4a` (`res` is the SMALLEST dimension, the best one at or under N first, the smallest above N only if nothing is that small), `-f` has NO codec filter (an "H.264 first" selector took a 362x640 H.264 over the 480x848 VP9 it sat beside), and `--merge-output-format mp4` makes every merge an .mp4. The only special case is Facebook's progressive `hd` (see "Facebook" below). Nothing is re-encoded. A single-file source in another container on a rare host keeps its own (`--remux-video mp4` was tried: it fails the whole download for Vorbis). |
| DP-2 | **"MP3 320kbps" is not 320 kbps.** `--audio-quality 0` is LAME's V0 VBR: measured 139-269 kbps over the eleven videos tried (about 245 typical). The label and the Windows transcode preset `mp3-320` (libmp3lame `-b:a 320k`, CBR) say 320. | owner-reported; WIN:src/pages/NewDownload.tsx:124 | RC 1.0.4: `--audio-quality 320K` (yt-dlp passes a value above 10 as `-b:a 320k`): every packet 1044/1045 bytes (44.1 kHz) or 960 (48 kHz), i.e. CBR 320. A source that already is MP3 is kept as it is, so it can be below 320 (yt-dlp never re-encodes it; SoundCloud's `http_mp3` is 128 kbps, but `bestaudio` there picks the AAC 96k, which is converted to CBR 320). |
| DP-3 | **"Opus (smallest)" does not make Opus.** `bestaudio[ext=webm]/bestaudio/best` with a bare `-x` keeps the source codec: only YouTube (Opus in webm) gives an .opus; SoundCloud (MP3 / AAC only), X, LinkedIn, Facebook, Instagram, Vimeo and Dailymotion save an AAC .m4a. `[ext=webm]` also excludes Opus in other containers. | owner-reported; WIN:src/pages/NewDownload.tsx:125 | RC 1.0.4: `bestaudio/best` with `-x --audio-format opus --audio-quality 128K`: an Opus source (YouTube 251) is copied untouched, anything else is encoded with libopus at 128 kbps (the Windows `opus-160` transcode preset is a different tool, 160k is the parity value if wanted). |
<!-- append DP rows above this line -->

### The strings to port to the Windows `DOWNLOAD_PRESETS`

```ts
// best-720
desc: 'Up to 720p, smaller file',   // was "Smaller file, good quality"
format: "b[format_id=hd][ext=mp4][url~='[?&]tag=(hd|dash_h264[a-z0-9_-]*_720p)(&|$)']/bv*+ba/b",
extraArgs: ['-S', 'res:720,vcodec:h264,acodec:aac,proto,ext:mp4:m4a', '--merge-output-format', 'mp4'],
// best-1080: the same, with two guards in front (a format whose short side is in (720, 1080]: width 721-1080 and height > 1080,
// or height 721-1080 and width > 720) so that the Facebook hd (a 720p file) can never beat a bigger format
desc: 'Sharpest up to 1080p',        // was "H.264 1080p, widely compatible"
format: "bv*[width>720][width<=1080][height>1080]+ba/bv*[height>720][height<=1080][width>720]+ba/b[format_id=hd][ext=mp4][url~='[?&]tag=(hd|dash_h264[a-z0-9_-]*_720p)(&|$)']/bv*+ba/b",
extraArgs: ['-S', 'res:1080,vcodec:h264,acodec:aac,proto,ext:mp4:m4a', '--merge-output-format', 'mp4'],
// audio-mp3
extraArgs: ['-x', '--audio-format', 'mp3', '--audio-quality', '320K'],
// audio-opus
format: 'bestaudio/best',
extraArgs: ['-x', '--audio-format', 'opus', '--audio-quality', '128K'],
```

Why `res` is first and H.264 only a tie-break, and why `proto` is in the sort: `-S` ranks by its first key and only then by the next, so
`res:N,vcodec:h264` is "the sharpest, and H.264 if two are as sharp". `proto` (https before HLS) sits before `ext:mp4`, as it does in
yt-dlp's own default order, because YouTube offers the same VP9 twice (a `.webm` over https and an HLS `.mp4`) and `ext` alone took the HLS
one.

### What yt-dlp can and cannot see, per site

yt-dlp ranks only what it can read: `res` (the smaller of width / height) and the codec. A format with no size ranks below every
format that has one inside the cap (and above one that is over the cap), and a format with no codec ranks after H.264 and before
HEVC / VP9 / AV1. ffprobe on real downloads of the unknown ones:

| Site | What yt-dlp sees | ffprobe of what yt-dlp cannot see |
|---|---|---|
| YouTube | every format: codec and size | n/a |
| Facebook | DASH video: VP9 / AV1, size known. Progressive `sd` / `hd`: nothing ("unknown") | `sd`: H.264 Constrained Baseline / Main, 224-640 px, on all ten videos. `hd`: depends on the CDN `tag`, see "Facebook" |
| Instagram | DASH video: H.264, size known. Progressive `1` / `2` / `3`: nothing | all three: H.264 Main 720x1280, the same stream as the best DASH rung |
| LinkedIn | `0` / `1` / `2`: nothing (bitrate only) | H.264 Main 714x360, 1270x640, 1428x720 |
| X | `hls-*`: H.264, size known. `http-*`: size, no codec | all three H.264 (Main 272x270 / 364x360, High 728x720) |
| Vimeo | `hls-*`: H.264, size known. `http-*`: size, no codec | H.264 High 640x360, 1280x720 |
| Dailymotion | muxed HLS: H.264 + AAC, size known | n/a |

Where known-size formats exist they are compared by their real size (the unknown progressive files are the same encodes as the
top DASH / HLS rung on Instagram, X and Vimeo, and H.264 wins that tie). Where the unknown ones are all there is (LinkedIn, an
older Facebook video), the extractor's own `quality` / bitrate picks: LinkedIn's `2` (the biggest), Facebook's `hd` over `sd`.

### What the MP4 presets give (selection by `--print`, then a real download and ffprobe; media deleted afterwards)

Format ids are yt-dlp's. "1.0.3" is the old `[height<=N]` chain, "RC" the first release candidate (419d299: H.264 first, then
Facebook sd, then anything), "new" this change. 720p / 1080p are the same unless two entries are shown.

| Site (video) | 1.0.3 | RC (419d299) | new, ffprobe of the file |
|---|---|---|---|
| YouTube `jNQXAC9IVRw` (max 320x240) | 133+140 | same | same: H.264 320x240 + AAC |
| YouTube `aqz-KE-bpKQ` | 298+140 / 299+140 | same | same: H.264 1280x720 / 1920x1080 + AAC |
| YouTube Short `BGQWPY4IigY` (H.264 stops at 608x1080, VP9 / AV1 reach 720x1280) | 136+140 H.264 406x720 / 137+140 H.264 608x1080 | 137+140 H.264 608x1080 | **247+140: VP9 720x1280 + AAC in an .mp4** (both presets) |
| LinkedIn (MathWorks) | `2` | same | same: H.264 1428x720 + AAC |
| Facebook reel `1195289147628387` (DASH VP9 360x636 / 480x848) | VP9 360x636 / 480x848 | `sd`: H.264 362x640 | **VP9 480x848 DASH + AAC** (both) |
| Facebook video `1370361647863285` (DASH AV1 540x960, six at 720x1280, 1080x1920; `hd` VP9 1080x1920) | `hd` (VP9 1080x1920, above the 720 cap) / AV1 540x960 | `sd`: H.264 360x640 | **720p: AV1 720x1280 (1392k) + AAC; 1080p: AV1 1080x1920 + AAC** |
| Facebook old `10153231379946729` (`sd` / `hd` only) | `hd` | `hd` | `hd`: H.264 High 1280x720 + AAC |
| Facebook watch `647537299265662` (DASH VP9 up to 720x1280; `hd` = `dash_h264..._720p`) | VP9 360x640 / 540x960 | `hd` | `hd`: H.264 High 720x1280 + AAC (H.264 wins the tie with the 720x1280 VP9; both presets) |
| Facebook `10155529876156509` (`sd` / `hd` only; `hd` = `compressed_source`) | `hd` | `sd`: H.264 400x400 | **`hd`: VP9 1080x1080** (above a 720 cap: yt-dlp cannot see it) |
| Facebook `359649331226507` (`sd` / `hd` only; `hd` = `av1_compressed_source`, 75 min) | `hd` | `sd`: H.264 400x224 | **`hd`: AV1 1920x1080** (first 8 s probed; above a 720 cap) |
| Facebook `sd` only: `106560053808006`, `10154383743583686`, `video.php?v=274175099429670` and `v=10153317450565268` | `sd` | `sd` | `sd`: H.264 224x400, 400x224, 400x300, 400x292 |
| Instagram `CDUMkliABpa` | DASH 322x572 / 504x896 (long-side cap) | 720x1280 | same: H.264 720x1280 + AAC |
| Vimeo `player.vimeo.com/video/98044508` | HLS 1280x720 | same | same: H.264 High 1280x720 + AAC |
| X `1790637656616943991` | `hls-716` | same | same: H.264 High 728x720 + AAC |
| Dailymotion `x94cnnk` | `hls-480` | same | same: H.264 272x480 + AAC |

Merging a VP9 / AV1 video with AAC into an .mp4 was also run with the host build of the app's own FFmpeg recipe (the same source,
git-2026-08-30, and libraries as `libsieveffmpeg.so` minus x264 / x265 and the Android flags; the merge is a stream copy, so no codec
library is involved): VP9 480x848, VP9 720x1280 (from a `.webm`) and AV1 1080x1920 merged into valid .mp4 files.

### Facebook (what the MP4 presets give, and why)

Facebook's progressive `sd` and `hd` have no codec, size or note for yt-dlp (it prints `unknown`), and the codec behind `hd` is
not constant. The CDN URL ends in `&tag=<encode>`; ffprobe on the ten videos, old and new, shows what it means:

| tag (`format_id`) | what ffprobe found |
|---|---|
| `sd`, `sve_sd` (`sd`) | H.264 Constrained Baseline / Main, 224-640 px (all ten videos) |
| `hd` (`hd`) | H.264 High 1280x720, AAC (an old video, uploaded about twelve years ago) |
| `dash_h264-basic-gen2_720p` (`hd`) | H.264 High 720x1280, AAC, while its DASH video is VP9 (up to 720x1280) |
| `compressed_source` (`hd`) | **VP9**: reel 480x848, videos 1080x1080 and 1080x1920; DASH video is VP9 / AV1 |
| `av1_compressed_source` (`hd`) | **AV1** 1920x1080 (a 75-minute video; probed with a real 8-second download) |

So the owner's decision decides Facebook like this:

1. **DASH video exists** (every modern video, and every `compressed_source` one): the best DASH rung inside the cap, VP9 or AV1
   in an .mp4, never the H.264 `sd` (the owner: "save the HD version as VP9/AV1 inside the .mp4, as 1.0.3 did. Sharper").
2. **`hd` is H.264 at 720p** (`tag=hd`, `tag=dash_h264...720p`): yt-dlp cannot see its size, so the selector ranks it as the 720p
   H.264 it is. Against a VP9 / AV1 DASH of the same 720p it wins (H.264 breaks the tie); a bigger DASH rung beats it (the two guards
   in `best-1080`, which only the 1080 cap can have), a smaller one loses to it. A tag that is not exactly H.264 at 720p
   (`...1080p`, a new one) falls back to being an unknown-size file.
3. **No DASH video, only `sd` / `hd`**: `hd` (an older video's H.264 720p, or a `compressed_source` VP9 / AV1 at the upload's own
   size), as 1.0.3 did. yt-dlp cannot see how big a `compressed_source` is, so **it can be above the cap** (1080x1080 and 1920x1080
   under a 720p preset in the tests above). `sd` is only taken when it is all there is.

Not changed on either platform: "4K / Best resolution" (`[height<=2160]`) has the same long-side flaw for vertical video (a
2160x3840 clip is excluded); `-S res:2160` would fix it the same way.

### Known limits of keep-resolution

* A video whose H.264 stops below the cap while VP9 / AV1 go higher gives the VP9 / AV1 (YouTube Shorts: 720x1280 VP9 over
  608x1080 H.264). That is the decision; the file is an .mp4 but not H.264.
* A lone muxed single file in another container on a rare host (a `.webm` VP8 + Vorbis) is not merged, so `--merge-output-format`
  does not apply and it stays `.webm`; it could be the sharpest format where an H.264 .mp4 exists at a lower resolution.
* yt-dlp cannot rank a size it cannot read. A site whose sized formats are ALL above the cap, beside unknown-size ones, takes
  an unknown-size one (Facebook `hd` over a DASH ladder that starts above the cap: not seen on any real video).
