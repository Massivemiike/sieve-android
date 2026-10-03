# WINDOWS-BUGS: bugs found in the Windows app while working on Android

A running list of bugs of the Windows app (v0.3.1, commit `8fb32c5`) found while working on the Android app, for the Windows
app's to-do list. This file was created on the `rc/1.0.4-presets` branch for the preset fixes; the longer list of the same name
on `feat/full-parity` uses the same table columns and ids by area, so the two merge by appending (this section keeps its own
`DP-` prefix and its own marker line).

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
| DP-1 | **"1080p MP4" / "720p MP4" ("H.264 1080p, widely compatible") are neither H.264 nor sized by the short side.** The selectors `bestvideo[height<=N][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=N]+bestaudio/best[height<=N]/best` pin the container, not the codec: `[ext=mp4]` also matches VP9 / AV1 in MP4 (a Facebook reel saved VP9 in an .mp4; the owner also reports AV1 on YouTube, OW-1 on `feat/full-parity`). `[height<=N]` measures a portrait video by its long side (the 720p preset gives a 406x720 Short although the 608x1080 stream is inside a 720 short-side cap) and drops every format whose height yt-dlp cannot read (Facebook sd/hd, LinkedIn, Instagram progressive), so those fall through to `/best`. The `/best` tiers can also end in .webm / .mkv (no `--merge-output-format`). | owner-reported; WIN:src/pages/NewDownload.tsx:120-121 | RC 1.0.4: `DownloadPresets.MP4_FORMAT` (known H.264 + audio, then a known-H.264 file, then Facebook's progressive `sd`, only then anything) with `-S res:N,vcodec:h264,acodec:aac,ext:mp4:m4a --merge-output-format mp4`. Nothing is re-encoded. Facebook now gives its 360x640 H.264 `sd` (its `hd` is VP9). |
| DP-2 | **"MP3 320kbps" is not 320 kbps.** `--audio-quality 0` is LAME's V0 VBR: measured 139-269 kbps over the eleven videos tried (about 245 typical). The label and the Windows transcode preset `mp3-320` (libmp3lame `-b:a 320k`, CBR) say 320. | owner-reported; WIN:src/pages/NewDownload.tsx:124 | RC 1.0.4: `--audio-quality 320K` (yt-dlp passes a value of 10 or more as `-b:a 320k`): every packet 1044/1045 bytes (44.1 kHz) or 960 (48 kHz), i.e. CBR 320. A source that already is MP3 is left as it is (yt-dlp never re-encodes it). |
| DP-3 | **"Opus (smallest)" does not make Opus.** `bestaudio[ext=webm]/bestaudio/best` with a bare `-x` keeps the source codec: only YouTube (Opus in webm) gives an .opus; SoundCloud (MP3 / AAC only), X, LinkedIn, Facebook, Instagram, Vimeo and Dailymotion save an AAC .m4a. `[ext=webm]` also excludes Opus in other containers. | owner-reported; WIN:src/pages/NewDownload.tsx:125 | RC 1.0.4: `bestaudio/best` with `-x --audio-format opus --audio-quality 128K`: an Opus source (YouTube 251) is copied untouched, anything else is encoded with libopus at 128 kbps (the Windows `opus-160` transcode preset is a different tool, 160k is the parity value if wanted). |
<!-- append DP rows above this line -->

### The strings to port to the Windows `DOWNLOAD_PRESETS`

```ts
// best-1080 and best-720 (the cap is the only difference; 1080: res:1080)
format: "bv[vcodec~='^(avc|h264)']+ba/b[vcodec~='^(avc|h264)']/b[format_id=sd][ext=mp4]/bv*+ba/b",
extraArgs: ['-S', 'res:720,vcodec:h264,acodec:aac,ext:mp4:m4a', '--merge-output-format', 'mp4'],
// audio-mp3
extraArgs: ['-x', '--audio-format', 'mp3', '--audio-quality', '320K'],
// audio-opus
format: 'bestaudio/best',
extraArgs: ['-x', '--audio-format', 'opus', '--audio-quality', '128K'],
```

Not changed on either platform: "4K / Best resolution" (`[height<=2160]`) has the same long-side flaw for vertical video (a
2160x3840 clip is excluded); `-S res:2160` would fix it the same way.
