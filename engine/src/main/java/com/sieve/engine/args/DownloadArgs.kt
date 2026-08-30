package com.sieve.engine.args

/**
 * Builds the download command prefix + URL around a YtdlpArgs vector.
 * Android-exempt: NO --ffmpeg-location (the library bundles ffmpeg). Keeps
 * --no-warnings and -c. `-c` is idempotent on resume.
 */
object DownloadArgs {
    const val PROGRESS_TEMPLATE =
        "%(progress._percent_str)s|%(progress._speed_str)s|%(progress._eta_str)s|%(progress.fragment_index)s/%(progress.fragment_count)s"

    val VALID_SCHEME = Regex("^(https?|ftp|ftps|rtmp|rtmps|rtsp|mms)://", RegexOption.IGNORE_CASE)

    fun buildCommandTokens(url: String, args: List<String>): List<String> =
        listOf("--newline", "-c", "--no-warnings", "--progress-template", PROGRESS_TEMPLATE) + args + url

    fun ensureContinue(args: List<String>): List<String> =
        if (args.contains("-c")) args else listOf("-c") + args

    fun isValidUrl(url: String): Boolean = VALID_SCHEME.containsMatchIn(url.trim())
}
