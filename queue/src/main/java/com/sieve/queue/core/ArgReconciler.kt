package com.sieve.queue.core

import com.sieve.engine.args.YtdlpArgs

/**
 * Pure list transforms that keep a QUEUED download's `engineArgs` correct: ensure `-c` for resume,
 * rewrite/strip flag pairs when global settings change, and inject the physical `-P`/`-o` at spawn
 * time from a [PreparedOutput] — the physical path is NEVER baked into the persisted args (plan #4's
 * output seam resolves it just-in-time). The url is appended by the port at call time, not here.
 */
object ArgReconciler {
    fun ensureContinue(args: List<String>): List<String> =
        if (args.contains("-c")) args else listOf("-c") + args

    fun rewriteFlagValue(args: List<String>, flag: String, value: String): List<String> {
        val out = ArrayList<String>(args.size + 2)
        var i = 0
        var replaced = false
        while (i < args.size) {
            if (args[i] == flag && i + 1 < args.size) {
                out += flag; out += value; i += 2; replaced = true
            } else {
                out += args[i]; i++
            }
        }
        if (!replaced) { out += flag; out += value }
        return out
    }

    fun stripFlagValue(args: List<String>, flag: String): List<String> {
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            if (args[i] == flag && i + 1 < args.size) i += 2 else { out += args[i]; i++ }
        }
        return out
    }

    fun stripFlag(args: List<String>, flag: String): List<String> = args.filterNot { it == flag }

    fun injectDownloadOutput(args: List<String>, prepared: PreparedOutput): List<String> {
        var a = stripFlagValue(args, "-P")
        a = stripFlagValue(a, "--paths")
        a = stripFlagValue(a, "-o")
        a = stripFlagValue(a, "--output")
        a = stripExactPair(a, PARSE_METADATA, TITLE_COPY_ACTION) // idempotent: never two copies of our own action
        val template = byteSafeTemplate(prepared.workFileTemplate)
        a = a + listOf("-P", prepared.workDir, "-o", template) + titleCopyArgsFor(template)
        // A user-chosen archive wins; otherwise the job's own, so Retry never re-downloads finished entries.
        val archive = prepared.archivePath
        return if (archive != null && "--download-archive" !in a) a + listOf("--download-archive", archive) else a
    }

    /**
     * yt-dlp scratch field the file name is cut from: the title after yt-dlp's OWN filename sanitizer
     * (`%(title)S`). Not `title` itself, so the embedded tags and the info JSON keep the real title. The `__`
     * prefix makes it a PRIVATE field: yt-dlp drops every `__` key when it writes an `.info.json`, so the scratch
     * copy never lands in the user's archive as a foreign key (a plain `sieve_title` did).
     */
    const val NAME_TITLE_FIELD = "__sieve_title"

    /**
     * What the name template cuts: `a,b` is yt-dlp's alternative - [NAME_TITLE_FIELD] when set, the real `title`
     * otherwise. The fallback is load-bearing: `--parse-metadata` only runs on VIDEOS, but yt-dlp names the
     * playlist-level files (`--write-info-json` on a playlist or channel URL, as the Archive preset does, also writes
     * `<playlist title> [<playlist id>].info.json`) from this same `-o`, evaluated on the PLAYLIST - which has no
     * scratch field. Without the fallback that file would be saved as `NA [<playlist id>].info.json`.
     */
    private const val NAME_TITLE_REF = "$NAME_TITLE_FIELD,title"

    /**
     * UTF-8 bytes of title kept in a file name. ext4 caps a name at 255 bytes, and yt-dlp appends
     * ` [id]` and, while it works, `.f<format-id>.<ext>.part` (fragmented streams: `.part-Frag<n>.part`)
     * - so this leaves 105 bytes for all of that.
     *
     * Two bounds this does NOT cover, both as in 1.0.3 (see FilenameByteBudgetTest):
     *  - the `[id]` is uncut: the 105 bytes hold ids up to about 40 bytes next to the longest side file name
     *    (about 85 for a plain `.part`), which covers the sites Sieve is tested on. Only the generic and direct-link
     *    extractors derive a long id (the URL's file name);
     *  - a PLAYLIST's own title has no scratch copy (see [NAME_TITLE_REF]), so a playlist-level `.info.json` still
     *    cuts the raw title: one made almost entirely of `? | :` can still overflow. Single videos are exact.
     */
    const val TITLE_BUDGET_BYTES = YtdlpArgs.TITLE_MAX_BYTES

    private const val PARSE_METADATA = "--parse-metadata"
    private const val TITLE_COPY_ACTION = "%(title)S:%($NAME_TITLE_FIELD)s"

    /** `%(title)s` (the older unbounded default) or `%(title).<n>B` (today's default, n = 150); captures n. */
    private val UNSAFE_TITLE = Regex("""%\(title\)(?:s|\.(\d+)B)""")

    /**
     * Make the title part of a file name byte-exact at spawn time, so jobs persisted with `%(title)s`
     * (a failed Facebook download being retried) or with `%(title).150B` cannot overflow ext4's 255-byte
     * limit. yt-dlp evaluates `%(title).150B` (cut raw bytes) BEFORE it sanitizes the result, and
     * sanitizing swaps each of `" * : < > ? | / \` for a 3-byte full-width look-alike - so a title of
     * mostly such characters grew from 150 B to 450 B. Cutting [NAME_TITLE_FIELD] (already sanitized) is
     * exact, and the look-alikes stay exactly yt-dlp's own, as on Windows.
     *
     * A cut a row already carries stays when it is tighter than [TITLE_BUDGET_BYTES] (it now counts sanitized bytes,
     * so it can only be safer); a looser or missing one becomes [TITLE_BUDGET_BYTES]. Internal: the output belongs
     * with [titleCopyArgsFor], which [injectDownloadOutput] always adds; alone it would just cut the raw title.
     */
    internal fun byteSafeTemplate(template: String): String =
        template.replace(UNSAFE_TITLE) { m ->
            val keep = m.groupValues[1].toIntOrNull()?.coerceAtMost(TITLE_BUDGET_BYTES) ?: TITLE_BUDGET_BYTES
            "%($NAME_TITLE_REF).${keep}B"
        }

    /**
     * `--parse-metadata %(title)S:%(__sieve_title)s` when [template] reads that field; nothing otherwise. The target
     * is spelled `%(field)s`: the yt-dlp inside the APK (2025.11.12) reads a bare `__sieve_title` as a literal regex
     * and sets nothing. The log gains one `[MetadataParser] Parsed ...` line per video.
     */
    private fun titleCopyArgsFor(template: String): List<String> =
        if (template.contains("%($NAME_TITLE_FIELD")) listOf(PARSE_METADATA, TITLE_COPY_ACTION) else emptyList()

    private fun stripExactPair(args: List<String>, flag: String, value: String): List<String> {
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            if (args[i] == flag && i + 1 < args.size && args[i + 1] == value) i += 2 else { out += args[i]; i++ }
        }
        return out
    }

    /**
     * The shortest thing yt-dlp can add to "<title> [<id>]" while a download is in flight: the " [" and "]" around a
     * one-byte id (4 bytes) and the part file's ".<ext>.part" with a two-letter extension (8). A separate-stream download
     * adds ".f<format>" on top of that; with an id and a format id of real length the overhead is several times this.
     */
    private const val MIN_NAME_OVERHEAD_BYTES = 12

    /**
     * What yt-dlp adds to a title on Facebook, whose titles are whole post captions, while one stream of a video is in
     * flight: " [<id>]" and ".f<format id>.<ext>.part" with the lengths of real videos (measured on
     * facebook.com/facebook/videos/1370361647863285: a 16-digit id, the format ids "<16 digits>v" and "<16 digits>a", mp4)
     * = 2 + 16 + 1 + 2 + 17 + 1 + 3 + 5 = 47 bytes. An estimate (a video with only a progressive format has no ".f<format>"
     * and adds 28), which is why [likelyOverflowedFileName] is a different, weaker, claim than [overflowedFileName].
     */
    private const val FACEBOOK_NAME_OVERHEAD_BYTES = 47

    /** The old default's unbounded title field: rows saved before 1.0.4 carry it (the clamp is applied when yt-dlp is spawned, never stored). */
    private const val UNBOUNDED_TITLE = "%(title)s"

    /**
     * Is the job's template one that still lets a title run to any length (the old default, which [byteSafeTemplate] clamps at
     * spawn)? Deliberately not "does [byteSafeTemplate] change it": that also rewrites a template that already cuts the title
     * (`%(title).150B`, today's default) to cut the sanitized copy, so it would put every row's title under suspicion.
     */
    private fun unclampedTitle(template: String): Boolean = UNBOUNDED_TITLE in template

    private fun titleBytes(title: String): Int = title.toByteArray(Charsets.UTF_8).size

    /**
     * Did [template], the `-o` template a job was stored with, make yt-dlp name a file for a media titled [title] with more
     * than ext4's [YtdlpArgs.MAX_FILENAME_BYTES] bytes, so that "[Errno 36] File name too long" is what stopped it? Used to
     * explain a row from before the fix, whose yt-dlp error was never stored.
     *
     * It is true only when it is PROVEN: the template still carries an unbounded `%(title)s` (the old default, the one
     * [byteSafeTemplate] now clamps; a template that already clamps the title cannot have overflowed because of it), and the
     * title's UTF-8 bytes plus the least yt-dlp adds ([MIN_NAME_OVERHEAD_BYTES]) are already past the limit. The row stores
     * neither the media's id nor yt-dlp's format id, so a title that overflowed only with a long id and format id is not
     * proven here; see [likelyOverflowedFileName] for those.
     *
     * Not counted: yt-dlp rewrites some characters of a title for the file name ('?' and control characters go, '/' ':' '"'
     * and a few more become 3-byte look-alikes), a few bytes either way. And a playlist row's title is the playlist's, not
     * its entries'.
     */
    fun overflowedFileName(template: String, title: String): Boolean =
        unclampedTitle(template) && titleBytes(title) + MIN_NAME_OVERHEAD_BYTES > YtdlpArgs.MAX_FILENAME_BYTES

    /**
     * [overflowedFileName], or a title that overflowed once yt-dlp's part-file suffix is counted at the length it really has
     * on [site] (the yt-dlp extractor name the row was created with; "facebook", "facebook:reel", ...). Only Facebook is
     * known well enough to say: its captions are the titles the 1.0.4 clamp was written for, and with a 16-digit id and a
     * 17-byte format id they overflow from 209 bytes on, not the 244 that [overflowedFileName] can prove. Any other site is
     * as before, so a long title elsewhere is not blamed on a guess.
     *
     * Still an estimate: a Facebook video that was a single progressive file had a shorter suffix (see
     * [FACEBOOK_NAME_OVERHEAD_BYTES]). The caller words it as a likely cause, not a certain one.
     */
    fun likelyOverflowedFileName(template: String, title: String, site: String): Boolean =
        overflowedFileName(template, title) ||
            (unclampedTitle(template) && isFacebook(site) &&
                titleBytes(title) + FACEBOOK_NAME_OVERHEAD_BYTES > YtdlpArgs.MAX_FILENAME_BYTES)

    /** yt-dlp's extractor names: "facebook", "facebook:reel", "facebook:ads", ... */
    private fun isFacebook(site: String): Boolean = site.substringBefore(':').equals("facebook", ignoreCase = true)

    /** Invariant flags the desktop main process prepends. Progress-template omitted: the engine
     *  module owns its own progress parsing. url is appended by the port at call time. */
    private val INVARIANT = listOf("--newline", "-c", "--no-warnings")

    fun buildSpawnArgs(spec: JobSpec.Download, prepared: PreparedOutput): List<String> {
        val body = injectDownloadOutput(ensureContinue(spec.engineArgs), prepared)
        // ensureContinue already added -c; INVARIANT also lists -c → drop the leading -c to dedup.
        val bodyNoDupContinue = if (body.firstOrNull() == "-c") body.drop(1) else body
        return INVARIANT + bodyNoDupContinue
    }
}
