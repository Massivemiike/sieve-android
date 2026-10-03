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
SoundCloud, Facebook (a reel and a video), Instagram, Vimeo, X and Dailymotion with yt-dlp 2026.08.19 + ffprobe. The Windows
`DOWNLOAD_PRESETS` array holds the same three bugs (`NewDownload.tsx:118-126`), in the same strings.

| Id | Windows behaviour | Evidence | Android |
|---|---|---|---|
| DP-1 | **"1080p MP4" / "720p MP4" ("H.264 1080p, widely compatible") are neither H.264 nor sized by the short side.** The selectors `bestvideo[height<=N][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=N]+bestaudio/best[height<=N]/best` pin the container, not the codec: `[ext=mp4]` also matches VP9 / AV1 in MP4 (a Facebook reel saved VP9 in an .mp4; the owner also reports AV1 on YouTube, OW-1 on `feat/full-parity`). `[height<=N]` measures a portrait video by its long side (the 720p preset gives a 406x720 Short although the 608x1080 stream is inside a 720 short-side cap) and drops every format whose height yt-dlp cannot read (Facebook sd/hd, LinkedIn, Instagram progressive), so those fall through to `/best`. The `/best` tiers can also end in .webm / .mkv (no `--merge-output-format`). | owner-reported; WIN:src/pages/NewDownload.tsx:120-121 | RC 1.0.4: `DownloadPresets.MP4_FORMAT` (known H.264 + audio, then a known-H.264 file, then Facebook's progressive `hd` only if its CDN `tag` says H.264, then Facebook's progressive `sd`, only then anything) with `-S res:N,vcodec:h264,acodec:aac,ext:mp4:m4a --merge-output-format mp4`. Nothing is re-encoded. `--merge-output-format` makes a no-H.264 fallback that is a merge (VP9 + Opus) an .mp4; a single-file source in another container on a rare host keeps its own (`--remux-video mp4` was tried: it fails the whole download for Vorbis). See "Facebook" below. |
| DP-2 | **"MP3 320kbps" is not 320 kbps.** `--audio-quality 0` is LAME's V0 VBR: measured 139-269 kbps over the eleven videos tried (about 245 typical). The label and the Windows transcode preset `mp3-320` (libmp3lame `-b:a 320k`, CBR) say 320. | owner-reported; WIN:src/pages/NewDownload.tsx:124 | RC 1.0.4: `--audio-quality 320K` (yt-dlp passes a value above 10 as `-b:a 320k`): every packet 1044/1045 bytes (44.1 kHz) or 960 (48 kHz), i.e. CBR 320. A source that already is MP3 is kept as it is, so it can be below 320 (yt-dlp never re-encodes it; SoundCloud's `http_mp3` is 128 kbps, but `bestaudio` there picks the AAC 96k, which is converted to CBR 320). |
| DP-3 | **"Opus (smallest)" does not make Opus.** `bestaudio[ext=webm]/bestaudio/best` with a bare `-x` keeps the source codec: only YouTube (Opus in webm) gives an .opus; SoundCloud (MP3 / AAC only), X, LinkedIn, Facebook, Instagram, Vimeo and Dailymotion save an AAC .m4a. `[ext=webm]` also excludes Opus in other containers. | owner-reported; WIN:src/pages/NewDownload.tsx:125 | RC 1.0.4: `bestaudio/best` with `-x --audio-format opus --audio-quality 128K`: an Opus source (YouTube 251) is copied untouched, anything else is encoded with libopus at 128 kbps (the Windows `opus-160` transcode preset is a different tool, 160k is the parity value if wanted). |
<!-- append DP rows above this line -->

### The strings to port to the Windows `DOWNLOAD_PRESETS`

```ts
// best-1080 and best-720 (the cap is the only difference; 1080: res:1080)
format: "bv[vcodec~='^(avc|h264)']+ba/b[vcodec~='^(avc|h264)']/b[format_id=hd][ext=mp4][url~='[?&]tag=(hd|dash_h264[a-z0-9_-]*)(&|$)']/b[format_id=sd][ext=mp4]/bv*+ba/b",
extraArgs: ['-S', 'res:720,vcodec:h264,acodec:aac,ext:mp4:m4a', '--merge-output-format', 'mp4'],
// audio-mp3
extraArgs: ['-x', '--audio-format', 'mp3', '--audio-quality', '320K'],
// audio-opus
format: 'bestaudio/best',
extraArgs: ['-x', '--audio-format', 'opus', '--audio-quality', '128K'],
```

### Facebook (what the MP4 presets give, and why)

Facebook's progressive `sd` and `hd` have no codec, size or note for yt-dlp (it prints `unknown`), and the codec behind `hd` is
not constant. The CDN URL ends in `&tag=<encode>`; ffprobe on eight videos, old and new, shows what it means:

| tag (`format_id`) | what ffprobe found |
|---|---|
| `sd`, `sve_sd` (`sd`) | H.264 Constrained Baseline / Main, 224-640 px (all eight videos) |
| `hd` (`hd`) | H.264 High 1280x720, AAC (an old video, uploaded about twelve years ago) |
| `dash_h264-basic-gen2_720p` (`hd`) | H.264 High 720x1280, AAC, while its DASH video is VP9 |
| `compressed_source` (`hd`) | **VP9** (reel 480x848, videos 1080x1080 and 1080x1920); DASH video is VP9 / AV1 |
| `av1_compressed_source` (`hd`) | AV1 by its name (a 75-minute video, not probed) |

So the earlier claim "Facebook's `hd` is VP9" was too broad: it is a VP9 / AV1 re-encode on many modern videos and the best
H.264 on others, and only the tag tells. The MP4 presets take `hd` only when the tag is `hd` or `dash_h264...`, otherwise `sd`:
an older sd/hd-only video gives its 720p H.264 `hd` (the 1.0.3 chain did too; the first RC took the 224p `sd`), a modern one
gives the H.264 `sd` (a few hundred pixels, but H.264 in an .mp4 as the preset promises). A tag that is not listed (new or
renamed) falls to `sd`, never to VP9 / AV1. Owner decision if wanted: resolution over H.264 on modern Facebook videos is
`b[format_id=hd][ext=mp4]` in place of the tag rule (the VP9 `hd` is back inside the .mp4); getting both would need a codec
probe after the download, which is out of scope for the RC.

Not changed on either platform: "4K / Best resolution" (`[height<=2160]`) has the same long-side flaw for vertical video (a
2160x3840 clip is excluded); `-S res:2160` would fix it the same way.
