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
        a = a + listOf("-P", prepared.workDir, "-o", byteSafeTemplate(prepared.workFileTemplate))
        // A user-chosen archive wins; otherwise the job's own, so Retry never re-downloads finished entries.
        val archive = prepared.archivePath
        return if (archive != null && "--download-archive" !in a) a + listOf("--download-archive", archive) else a
    }

    /**
     * Clamp an unbounded `%(title)s` to 150 bytes at spawn time, so jobs persisted with the old
     * template (e.g. a failed Facebook download being retried) can't overflow ext4's 255-byte
     * filename limit either.
     */
    fun byteSafeTemplate(template: String): String =
        template.replace("%(title)s", "%(title).${YtdlpArgs.TITLE_MAX_BYTES}B")

    /**
     * The shortest thing yt-dlp can add to "<title> [<id>]" while a download is in flight: the " [" and "]" around a
     * one-byte id (4 bytes) and the part file's ".<ext>.part" with a two-letter extension (8). A separate-stream download
     * adds ".f<format>" on top of that; with an id and a format id of real length the overhead is several times this.
     */
    private const val MIN_NAME_OVERHEAD_BYTES = 12

    /**
     * Did [template], the `-o` template a job was stored with, make yt-dlp name a file for a media titled [title] with more
     * than ext4's [YtdlpArgs.MAX_FILENAME_BYTES] bytes, so that "[Errno 36] File name too long" is what stopped it? Used to
     * explain a row from before the fix, whose yt-dlp error was never stored.
     *
     * It is true only when it is PROVEN: the template still carries an unbounded `%(title)s` (the old default, the one
     * [byteSafeTemplate] now clamps; a template that already clamps the title cannot have overflowed because of it), and the
     * title's UTF-8 bytes plus the least yt-dlp adds ([MIN_NAME_OVERHEAD_BYTES]) are already past the limit. The row stores
     * neither the media's id nor yt-dlp's format id, so a title that overflowed only with a long id and format id is not
     * recognised; it is explained as an unknown failure, which is true as well, and Retry works for it all the same.
     */
    fun overflowedFileName(template: String, title: String): Boolean =
        byteSafeTemplate(template) != template &&
            title.toByteArray(Charsets.UTF_8).size + MIN_NAME_OVERHEAD_BYTES > YtdlpArgs.MAX_FILENAME_BYTES

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
