package com.sieve.queue.core

import com.sieve.engine.args.YtdlpArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "byte-safe-after-glyphs": yt-dlp cuts `%(title).150B` BEFORE its sanitizer turns `" * : < > ? | / \` into
 * 3-byte full-width look-alikes, so a title of mostly such characters became ~450 bytes and overflowed ext4's
 * 255-byte limit ("[Errno 36] File name too long"). The fix cuts a copy of the title that yt-dlp has already
 * sanitized. These tests render the spawn args the queue really builds through [YtdlpFilenameModel], which
 * is pinned to the real yt-dlp 2026.08.19 by [FILENAME_VECTORS].
 */
class FilenameByteBudgetTest {
    private val legacyArgs = listOf("-o", "%(title).150B [%(id)s].%(ext)s") // what 1.0.3 and the 1.0.4 RC spawned
    private val idExt = " [dQw4w9WgXcQ].mp4"

    private fun spawn(
        template: String = YtdlpArgs.DEFAULT_TEMPLATE,
        persisted: List<String> = listOf("-f", "best"),
    ): List<String> = ArgReconciler.buildSpawnArgs(
        JobSpec.Download("https://x", persisted), PreparedOutput("/work/job-a", template),
    )

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    /**
     * Every name yt-dlp writes next to a media file, longest first: a fragmented stream's chunk
     * (`.f<fmt>.<ext>.part-Frag<n>.part`), the resume marker, the merge temp, subtitles, the info JSON, a cover.
     * A 23-character format id (hls-avc1-1080p-5000-eng) is longer than the real ones on the sites Sieve is tested against.
     */
    private fun siblings(name: String): List<String> {
        val stem = name.removeSuffix(".mp4")
        val f = ".fhls-avc1-1080p-5000-eng"
        return listOf(
            name, "$stem$f.mp4", "$stem$f.mp4.part", "$stem$f.mp4.ytdl", "$stem$f.mp4.part-Frag999999",
            "$stem$f.mp4.part-Frag999999.part", "$stem.temp.mp4", "$stem.en-US.vtt", "$stem.zh-Hant-TW.srt",
            "$stem.info.json", "$stem.description", "$stem.webp",
        )
    }

    /** Titles that grow the most when yt-dlp sanitizes them, plus the real-world shapes (emoji, newlines, quotes). */
    private val worstCaseTitles: Map<String, String> = linkedMapOf(
        "450 question marks" to "?".repeat(450),
        "450 pipes" to "|".repeat(450),
        "all nine specials" to "\"*:<>?|/\\".repeat(50),
        "emoji and ?" to "😀?".repeat(150),
        "CJK and ?" to "漢?".repeat(200),
        "colon heavy" to "a:b?".repeat(110),
        "Facebook caption" to "Line one: why?\nLine two | \"quoted\" <b>bold</b> *star* ".repeat(30),
        "100 B of text then specials" to "x".repeat(100) + "?|:*<>\"".repeat(8),
    )

    @Test fun `the model reproduces the real yt-dlp for the old args on every captured title`() {
        for (v in FILENAME_VECTORS) {
            assertEquals(v.key, v.legacy, YtdlpFilenameModel.render(legacyArgs, v.title))
        }
    }

    @Test fun `the spawn args give the real yt-dlp's name for every captured title`() {
        val args = spawn()
        for (v in FILENAME_VECTORS) {
            assertEquals(v.key, v.fixed, YtdlpFilenameModel.render(args, v.title))
        }
    }

    @Test fun `worst-case titles keep every saved and intermediate name within 255 bytes`() {
        val args = spawn()
        for ((what, title) in worstCaseTitles) {
            val name = YtdlpFilenameModel.render(args, title)
            for (sibling in siblings(name)) {
                assertTrue("$what: ${bytes(sibling)} B for ${sibling.takeLast(40)}", bytes(sibling) <= 255)
            }
        }
    }

    @Test fun `the old args overflow on the same titles - the test can see the bug`() {
        val overflowing = worstCaseTitles.filterValues { title ->
            siblings(YtdlpFilenameModel.render(legacyArgs, title)).any { bytes(it) > 255 }
        }.keys
        assertEquals(worstCaseTitles.keys.toList(), overflowing.toList())
    }

    @Test fun `a cut title keeps yt-dlp's own look-alike glyphs - a prefix of the full sanitized title`() {
        val args = spawn()
        for ((what, title) in worstCaseTitles) {
            val part = YtdlpFilenameModel.render(args, title).removeSuffix(idExt)
            val full = YtdlpFilenameModel.sanitizeFilename(title)
            assertTrue("$what: not a prefix of the sanitized title", full.startsWith(part))
            // 150 bytes, give or take the half glyph the cut drops
            assertTrue("$what: ${bytes(part)} B", bytes(part) in 147..150)
        }
    }

    @Test fun `a title inside the budget keeps exactly the name Windows gives it`() {
        val args = spawn()
        for (title in listOf(
            "What Is MathWorks Cloud Center? | MathWorks", "Episode 12:30 Live: Part 2? Yes", "\"Quoted\" <title> *star* a/b\\c",
            "Line one\nLine two", "-- Topic | Video?", "漢字? 한글 | ok", "plain",
        )) {
            assertEquals(YtdlpFilenameModel.sanitizeFilename(title) + idExt, YtdlpFilenameModel.render(args, title))
        }
        // and the phone's own example, glyph for glyph: U+FF1F and U+FF5C
        assertEquals(
            "What Is MathWorks Cloud Center？ ｜ MathWorks [dQw4w9WgXcQ].mp4",
            YtdlpFilenameModel.render(args, "What Is MathWorks Cloud Center? | MathWorks"),
        )
    }

    @Test fun `a row persisted with any known template spelling is protected when it is spawned`() {
        for (template in listOf(
            "%(title).150B [%(id)s].%(ext)s", // 1.0.3 .. 1.0.4 RC
            "%(title)s [%(id)s].%(ext)s", // the older unbounded default (SafOutputProvider's fallback too)
            "%(title)s.%(ext)s",
        )) {
            val args = spawn(template)
            for ((what, title) in worstCaseTitles) {
                val name = YtdlpFilenameModel.render(args, title)
                assertTrue("$template / $what: ${bytes(name)} B", siblings(name).all { bytes(it) <= 255 })
            }
        }
    }

    @Test fun `a persisted row that already carries -o and -P gets them replaced and one title copy`() {
        val persisted = listOf("-f", "best", "-o", "%(title).150B [%(id)s].%(ext)s", "-P", "~/Videos/yt-dlp", "--embed-metadata")
        val args = spawn(persisted = persisted)
        assertEquals(1, args.count { it == "-o" })
        assertEquals(1, args.count { it == "-P" })
        assertEquals("/work/job-a", args[args.indexOf("-P") + 1])
        assertEquals(listOf("%(title)S:%(__sieve_title)s"), args.filterIndexed { i, _ -> i > 0 && args[i - 1] == "--parse-metadata" })
        assertTrue("--embed-metadata" in args)
        val name = YtdlpFilenameModel.render(args, "?".repeat(450))
        assertTrue(siblings(name).all { bytes(it) <= 255 })
    }

    @Test fun `spawning twice adds the title copy once`() {
        val prepared = PreparedOutput("/work/job-a", YtdlpArgs.DEFAULT_TEMPLATE)
        val once = ArgReconciler.injectDownloadOutput(listOf("-f", "best"), prepared)
        assertEquals(once, ArgReconciler.injectDownloadOutput(once, prepared))
        val withArchive = PreparedOutput("/work/job-a", YtdlpArgs.DEFAULT_TEMPLATE, archivePath = "/work/job-a.archive.txt")
        val twice = ArgReconciler.injectDownloadOutput(ArgReconciler.injectDownloadOutput(listOf("-f", "best"), withArchive), withArchive)
        assertEquals(1, twice.count { it == "--parse-metadata" })
        assertEquals(1, twice.count { it == "--download-archive" })
    }

    @Test fun `the title copy spells its target as a field - a bare word sets nothing on the engine inside the APK`() {
        val args = spawn()
        val action = args[args.indexOf("--parse-metadata") + 1]
        // yt-dlp 2025.11.12 reads a bare `__sieve_title` as a literal regex: nothing is set and every file would be `NA [id]`
        assertEquals("%(title)S:%(__sieve_title)s", action)
    }

    @Test fun `the scratch field is private - yt-dlp keeps a __ key out of the saved info json`() {
        // Real engines (desktop 2026.08.19, APK 2025.11.12): with a plain `sieve_title` the user's .info.json gained a foreign
        // top-level "sieve_title" key; yt-dlp drops every `__` key when it writes the JSON, and the file names are identical.
        assertTrue(ArgReconciler.NAME_TITLE_FIELD.startsWith("__"))
        val args = spawn()
        assertEquals("%(title)S:%(${ArgReconciler.NAME_TITLE_FIELD})s", args[args.indexOf("--parse-metadata") + 1])
        assertTrue(args.none { "%(sieve_title" in it })
    }

    @Test fun `a playlist-level file keeps its name - the scratch title exists only on videos`() {
        val args = spawn()
        for (v in PLAYLIST_VECTORS) {
            assertEquals(v.key, v.legacy, YtdlpFilenameModel.renderPlaylistInfoJson(legacyArgs, v.title, v.id))
            assertEquals(v.key, v.fixed, YtdlpFilenameModel.renderPlaylistInfoJson(args, v.title, v.id))
            assertEquals("${v.key}: the playlist file must be named as it was before the fix", v.legacy, v.fixed)
        }
    }

    @Test fun `without the title alternative every playlist file would be saved as NA - the first version of the fix`() {
        // Real engines: `%(sieve_title).150B` on a playlist run saved "NA [PLtest123].info.json" (Archive preset + any playlist URL).
        val noFallback = listOf("--parse-metadata", "%(title)S:%(__sieve_title)s", "-o", "%(__sieve_title).150B [%(id)s].%(ext)s")
        assertEquals(
            "NA [PLtest123].info.json",
            YtdlpFilenameModel.renderPlaylistInfoJson(noFallback, "My Archive: Best? | Of 2026", "PLtest123"),
        )
    }

    @Test fun `known residual - a playlist title of 150 bytes of question marks still overflows its own info json`() {
        // As in 1.0.3: the playlist dict has no scratch copy, so its .info.json cuts the RAW title (see ArgReconciler.TITLE_BUDGET_BYTES).
        // A video with the same title is exact.
        val v = PLAYLIST_VECTORS.single { it.key == "pl-overlong" }
        val playlistFile = YtdlpFilenameModel.renderPlaylistInfoJson(spawn(), v.title, v.id)
        assertEquals(v.fixed, playlistFile)
        assertTrue("${bytes(playlistFile)} B", bytes(playlistFile) > 255)
        assertTrue(siblings(YtdlpFilenameModel.render(spawn(), v.title)).all { bytes(it) <= 255 })
    }

    @Test fun `a cut a row already carries is kept when tighter than 150 and clamped to 150 when looser`() {
        for (v in TEMPLATE_VECTORS) {
            assertEquals(v.key, v.legacy, YtdlpFilenameModel.render(listOf("-o", v.persisted), v.title))
            assertEquals(v.key, v.fixed, YtdlpFilenameModel.render(spawn(v.persisted), v.title))
        }
    }

    @Test fun `a template without a title adds nothing`() {
        val args = spawn("%(id)s.%(ext)s")
        assertTrue("--parse-metadata" !in args)
        assertEquals("dQw4w9WgXcQ.mp4", YtdlpFilenameModel.render(args, "?".repeat(450)))
    }

    @Test fun `a caller's own parse-metadata survives`() {
        val own = listOf("--parse-metadata", "title:%(artist)s - %(track)s")
        val args = spawn(persisted = own)
        assertEquals(2, args.count { it == "--parse-metadata" })
        assertTrue(args.windowed(2).any { it == own })
    }
}
