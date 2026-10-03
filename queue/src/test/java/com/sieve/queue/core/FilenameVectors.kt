package com.sieve.queue.core

/**
 * Titles and the file names the REAL yt-dlp builds from them: the desktop 2026.08.19 (the engine the phone
 * self-updates to); the 2025.11.12 inside the APK gives the same names. Captured with
 * `yt-dlp --load-info-json <id=dQw4w9WgXcQ, ext=mp4, title=...> --simulate --print filename` and these two arg sets:
 *  - legacy: `-o "%(title).150B [%(id)s].%(ext)s"`
 *  - fixed:  `--parse-metadata "%(title)S:%(__sieve_title)s" -o "%(__sieve_title,title).150B [%(id)s].%(ext)s"`
 * [YtdlpFilenameModel] must reproduce every one of them, and the spawn args the queue really builds are
 * rendered through that model, so a drift between the model and yt-dlp, or between the args and the fix,
 * fails a test. Re-capture when the engine's `sanitize_filename` changes.
 */
internal data class FilenameVector(val key: String, val title: String, val legacy: String, val fixed: String)

internal val FILENAME_VECTORS: List<FilenameVector> = listOf(
    FilenameVector(
        "plain",
        title = "Rick Astley - Never Gonna Give You Up (Official Music Video)",
        legacy = "Rick Astley - Never Gonna Give You Up (Official Music Video) [dQw4w9WgXcQ].mp4",
        fixed = "Rick Astley - Never Gonna Give You Up (Official Music Video) [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "phone-linkedin",
        title = "What Is MathWorks Cloud Center? | MathWorks",
        legacy = "What Is MathWorks Cloud Center\uff1f \uff5c MathWorks [dQw4w9WgXcQ].mp4",
        fixed = "What Is MathWorks Cloud Center\uff1f \uff5c MathWorks [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "timestamp",
        title = "Episode 12:30 Live: Part 2? Yes 1:2:3 end",
        legacy = "Episode 12_30 Live\uff1a Part 2\uff1f Yes 1_2_3 end [dQw4w9WgXcQ].mp4",
        fixed = "Episode 12_30 Live\uff1a Part 2\uff1f Yes 1_2_3 end [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "all-nine",
        title = "\"Quoted\" <title> *star* a/b\\c? d|e: f",
        legacy = "\uff02Quoted\uff02 \uff1ctitle\uff1e \uff0astar\uff0a a\u29f8b\u29f9c\uff1f d\uff5ce\uff1a f [dQw4w9WgXcQ].mp4",
        fixed = "\uff02Quoted\uff02 \uff1ctitle\uff1e \uff0astar\uff0a a\u29f8b\u29f9c\uff1f d\uff5ce\uff1a f [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "newline-tab",
        title = "Line one\nLine two\tTabbed\r\nLine three",
        legacy = "Line one Line twoTabbed Line three [dQw4w9WgXcQ].mp4",
        fixed = "Line one Line twoTabbed Line three [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "leading-dash",
        title = "-- Topic | Video?",
        legacy = "-- Topic \uff5c Video\uff1f [dQw4w9WgXcQ].mp4",
        fixed = "-- Topic \uff5c Video\uff1f [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "leading-dot",
        title = ".hidden: file",
        legacy = ".hidden\uff1a file [dQw4w9WgXcQ].mp4",
        fixed = ".hidden\uff1a file [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "cjk",
        title = "\u6f22\u5b57?\u304b\u306a: \ud55c\uae00 | ok",
        legacy = "\u6f22\u5b57\uff1f\u304b\u306a\uff1a \ud55c\uae00 \uff5c ok [dQw4w9WgXcQ].mp4",
        fixed = "\u6f22\u5b57\uff1f\u304b\u306a\uff1a \ud55c\uae00 \uff5c ok [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "fullwidth-already",
        title = "\uff1f\uff5c already full-width \u29f8",
        legacy = "\uff1f\uff5c already full-width \u29f8 [dQw4w9WgXcQ].mp4",
        fixed = "\uff1f\uff5c already full-width \u29f8 [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "cut-mid-glyph-149",
        title = "a".repeat(149) + "?",
        legacy = "a".repeat(149) + "\uff1f [dQw4w9WgXcQ].mp4",
        fixed = "a".repeat(149) + " [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "cut-mid-glyph-148",
        title = "a".repeat(148) + "?",
        legacy = "a".repeat(148) + "\uff1f [dQw4w9WgXcQ].mp4",
        fixed = "a".repeat(148) + " [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "cut-exact-147",
        title = "a".repeat(147) + "?",
        legacy = "a".repeat(147) + "\uff1f [dQw4w9WgXcQ].mp4",
        fixed = "a".repeat(147) + "\uff1f [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "qmarks-200",
        title = "?".repeat(200),
        legacy = "\uff1f".repeat(150) + " [dQw4w9WgXcQ].mp4",
        fixed = "\uff1f".repeat(50) + " [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "pipes-150",
        title = "|".repeat(150) + " tail",
        legacy = "\uff5c".repeat(150) + " [dQw4w9WgXcQ].mp4",
        fixed = "\uff5c".repeat(50) + " [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "mixed-punct",
        title = "What? Who| Why: How* ".repeat(11) + "What? Who| Why: How*",
        legacy = "What\uff1f Who\uff5c Why\uff1a How\uff0a ".repeat(7) + "Wha [dQw4w9WgXcQ].mp4",
        fixed = "What\uff1f Who\uff5c Why\uff1a How\uff0a ".repeat(5) + "What [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "emoji-qmarks",
        title = "\ud83d\ude00?".repeat(100),
        legacy = "\ud83d\ude00\uff1f".repeat(30) + " [dQw4w9WgXcQ].mp4",
        fixed = "\ud83d\ude00\uff1f".repeat(21) + " [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "facebook-caption",
        title = "Line one: why?\nLine two | \"quoted\" <b>bold</b> *star* ".repeat(6),
        legacy = "Line one\uff1a why\uff1f Line two \uff5c \uff02quoted\uff02 \uff1cb\uff1ebold\uff1c\u29f8b\uff1e \uff0astar\uff0a Line one\uff1a why\uff1f Line two \uff5c \uff02quoted\uff02 \uff1cb\uff1ebold\uff1c\u29f8b\uff1e \uff0astar\uff0a Line one\uff1a why\uff1f Line two \uff5c \uff02quoted\uff02 \uff1cb\uff1ebold [dQw4w9WgXcQ].mp4",
        fixed = "Line one\uff1a why\uff1f Line two \uff5c \uff02quoted\uff02 \uff1cb\uff1ebold\uff1c\u29f8b\uff1e \uff0astar\uff0a Line one\uff1a why\uff1f Line two \uff5c \uff02quoted\uff02 \uff1cb\uff1ebold\uff1c\u29f8b\uff1e \uff0ast [dQw4w9WgXcQ].mp4",
    ),
    FilenameVector(
        "only-newline",
        title = "\n",
        legacy = "_ [dQw4w9WgXcQ].mp4",
        fixed = "_ [dQw4w9WgXcQ].mp4",
    ),
)

/**
 * The PLAYLIST-level file: yt-dlp names `<playlist title> [<playlist id>].info.json` (`--write-info-json` on a
 * playlist or channel URL, as the Archive preset does) from the same `-o`, evaluated on the playlist - where
 * `--parse-metadata` never ran, so the scratch title does not exist. Captured from a real playlist run
 * (`--load-info-json <playlist with one entry> --no-clean-info-json --skip-download --write-info-json`, then the
 * directory listing) on the desktop 2026.08.19 and the APK's 2025.11.12, same names; `pl-overlong` could not be
 * written (a 472-byte name), so it is `YoutubeDL.prepare_filename(playlist, 'pl_infojson')` on both.
 * The scratch title would make every one of these `NA [<id>].info.json`; the `%(__sieve_title,title)` alternative
 * keeps them exactly as before.
 */
internal data class PlaylistVector(val key: String, val id: String, val title: String, val legacy: String, val fixed: String)

internal val PLAYLIST_VECTORS: List<PlaylistVector> = listOf(
    PlaylistVector(
        "pl-plain", id = "PLabc123",
        title = "Best Of 2026 - Part 1",
        legacy = "Best Of 2026 - Part 1 [PLabc123].info.json",
        fixed = "Best Of 2026 - Part 1 [PLabc123].info.json",
    ),
    PlaylistVector(
        "pl-archive", id = "PLtest123",
        title = "My Archive: Best? | Of 2026",
        legacy = "My Archive\uff1a Best\uff1f \uff5c Of 2026 [PLtest123].info.json",
        fixed = "My Archive\uff1a Best\uff1f \uff5c Of 2026 [PLtest123].info.json",
    ),
    PlaylistVector(
        "pl-long-plain", id = "PLtest123",
        title = "p".repeat(160) + " end",
        legacy = "p".repeat(150) + " [PLtest123].info.json",
        fixed = "p".repeat(150) + " [PLtest123].info.json",
    ),
    PlaylistVector(
        "pl-specials-60", id = "PLtest123",
        title = "?".repeat(60),
        legacy = "\uff1f".repeat(60) + " [PLtest123].info.json",
        fixed = "\uff1f".repeat(60) + " [PLtest123].info.json",
    ),
    PlaylistVector(
        "pl-overlong", id = "PLtest123",
        title = "?".repeat(200),
        legacy = "\uff1f".repeat(150) + " [PLtest123].info.json",
        fixed = "\uff1f".repeat(150) + " [PLtest123].info.json",
    ),
)

/**
 * A row persisted with a cut of its own, `%(title).<persisted>B`, through the spawn-time rewrite: a cut tighter than
 * 150 stays (now counting sanitized bytes), a looser one becomes 150. Captured from the real engines like the rest
 * (a video, `id=dQw4w9WgXcQ`, `ext=mp4`); `legacy` is the persisted template as 1.0.3 spawned it.
 */
internal data class TemplateVector(val key: String, val persisted: String, val title: String, val legacy: String, val fixed: String)

internal val TEMPLATE_VECTORS: List<TemplateVector> = listOf(
    TemplateVector(
        "tight-80", persisted = "%(title).80B [%(id)s].%(ext)s",
        title = "?".repeat(200),
        legacy = "\uff1f".repeat(80) + " [dQw4w9WgXcQ].mp4",
        fixed = "\uff1f".repeat(26) + " [dQw4w9WgXcQ].mp4",
    ),
    TemplateVector(
        "clamp-400", persisted = "%(title).400B [%(id)s].%(ext)s",
        title = "?".repeat(200),
        legacy = "\uff1f".repeat(200) + " [dQw4w9WgXcQ].mp4",
        fixed = "\uff1f".repeat(50) + " [dQw4w9WgXcQ].mp4",
    ),
)
