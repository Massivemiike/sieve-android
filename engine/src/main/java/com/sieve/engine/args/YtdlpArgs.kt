package com.sieve.engine.args

/**
 * Pure port of the desktop `buildDownloadArgs` (src/lib/ytdlp-args.ts).
 * Ordering is a hard contract — tests lock it. Process-level flags
 * (--no-warnings, -c, --progress-template) are NOT added here; the download
 * command layer (DownloadArgs) owns those. On Android, --ffmpeg-location is
 * never added (the library bundles ffmpeg).
 */
object YtdlpArgs {
    /** Android's ext4 caps one file name at 255 BYTES (not characters; an emoji is 4). */
    const val MAX_FILENAME_BYTES = 255

    /** What `%(title).150B` lets a title take of [MAX_FILENAME_BYTES]; the rest is for " [<id>]" and yt-dlp's part-file suffix. */
    const val TITLE_MAX_BYTES = 150

    // Title clamped to 150 BYTES: Android's ext4 caps a filename at 255 bytes, and yt-dlp appends
    // ".f<format-id>.<ext>.part" while downloading separate streams. Facebook titles are the whole
    // post caption (emoji included), which overflowed it → "[Errno 36] File name too long".
    const val DEFAULT_TEMPLATE = "%(title).${TITLE_MAX_BYTES}B [%(id)s].%(ext)s"
    const val DEFAULT_OUTPUT_PATH = "~/Videos/yt-dlp"

    /** `"-x / --extract-audio"` → `"--extract-audio"`; no slash → unchanged. */
    internal fun canonicalFlag(flag: String): String =
        if (flag.contains('/')) flag.substringAfterLast('/').trim() else flag

    fun build(
        opts: DownloadArgsOptions,
        settings: EngineSettings,
        defaultOutputPath: String = DEFAULT_OUTPUT_PATH,
    ): List<String> {
        val args = mutableListOf<String>()

        // 1 format (unconditional, even when empty)
        args += "-f"; args += opts.format
        // 2 output template
        args += "-o"; args += (opts.outputTemplate?.ifEmpty { null } ?: DEFAULT_TEMPLATE)
        // 3 output path
        args += "-P"; args += (opts.outputPath?.ifEmpty { null }
            ?: settings.outputPath.ifEmpty { null } ?: defaultOutputPath)
        // 4 caller extra args (spread, in order)
        opts.extraArgs?.let { args += it }
        // 5 playlist items
        if (!opts.playlistItems.isNullOrEmpty()) { args += "--playlist-items"; args += opts.playlistItems }
        // 6 playlist end (0 is skipped)
        if (opts.playlistEnd != null && opts.playlistEnd != 0) { args += "--playlist-end"; args += opts.playlistEnd.toString() }
        // 7 archive: explicit opts suppresses the settings fallback (else-if)
        if (!opts.downloadArchive.isNullOrEmpty()) {
            args += "--download-archive"; args += opts.downloadArchive
        } else if (settings.autoArchive && settings.archiveFile.isNotBlank()) {
            args += "--download-archive"; args += settings.archiveFile.trim()
        }
        // 8 toggle loop
        opts.toggleOpts?.forEach { (flag, value) ->
            val actual = canonicalFlag(flag)
            when (value) {
                is ToggleValue.On -> if (opts.extraArgs?.contains(actual) != true) args += actual
                is ToggleValue.Text -> if (value.value.isNotEmpty()) { args += actual; args += value.value }
                ToggleValue.Off -> { /* ignore */ }
            }
        }
        // 9 speed limit ("0" and "" unset)
        val limited = !opts.speedLimit.isNullOrEmpty() && opts.speedLimit != "0"
        if (limited) { args += "--limit-rate"; args += opts.speedLimit!! }
        // 10 concurrent fragments (>1). yt-dlp throttles each fragment download on its own, so N parallel
        // fragments run at N x the limit (measured: 500K -> ~1.2 MiB/s on HLS with -N 4). A limit wins.
        // (Divergence from the desktop args, which still have that bug.)
        if (settings.concurrentFragments > 1 && !limited) { args += "-N"; args += settings.concurrentFragments.toString() }
        // 11 proxy
        if (settings.proxy.isNotEmpty()) { args += "--proxy"; args += settings.proxy }
        // 12 cookies from browser
        if (settings.cookiesBrowser.isNotEmpty()) { args += "--cookies-from-browser"; args += settings.cookiesBrowser }
        // 13 cookies file
        if (settings.cookiesFile.isNotEmpty()) { args += "--cookies"; args += settings.cookiesFile }
        // 14 user agent
        if (settings.userAgent.isNotEmpty()) { args += "--user-agent"; args += settings.userAgent }
        // 15 geo-bypass country
        if (settings.geoBypassCountry.isNotEmpty()) { args += "--geo-bypass-country"; args += settings.geoBypassCountry }
        // 16 custom headers (insertion order; skip blanks)
        settings.customHeaders.forEach { (name, value) ->
            if (name.isNotEmpty() && value.isNotEmpty()) { args += "--add-header"; args += "$name:$value" }
        }
        // 17 sleep interval (>0)
        if (settings.sleepInterval > 0) { args += "--sleep-interval"; args += settings.sleepInterval.toString() }
        // 18 max filesize (trimmed)
        if (settings.maxFilesize.isNotBlank()) { args += "--max-filesize"; args += settings.maxFilesize.trim() }
        // 19 subtitles ("all" wins; --write-subs deduped, --sub-langs not)
        if (!opts.subtitleLangs.isNullOrEmpty()) {
            val langSpec = if (opts.subtitleLangs.contains("all")) "all" else opts.subtitleLangs.joinToString(",")
            if (!args.contains("--write-subs")) args += "--write-subs"
            args += "--sub-langs"; args += langSpec
        }
        // 20 audio-only (dedup vs already-present -x)
        if (opts.audioOnly && !args.contains("-x")) args += "-x"
        // 21 thumbnail only
        if (opts.thumbnailOnly) { args += "--write-thumbnail"; args += "--skip-download" }
        // 22 info only
        if (opts.infoOnly) { args += "--write-info-json"; args += "--skip-download" }

        return args
    }
}
