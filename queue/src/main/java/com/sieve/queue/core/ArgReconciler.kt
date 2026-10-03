package com.sieve.queue.core

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
     * Not covered, as in 1.0.3 (see FilenameByteBudgetTest): a PLAYLIST's own title has no scratch copy (see
     * [NAME_TITLE_REF]), so a playlist-level `.info.json` still cuts the raw title - one made almost entirely of
     * `? | :` can still overflow. Single videos are exact.
     */
    const val TITLE_BUDGET_BYTES = 150

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
