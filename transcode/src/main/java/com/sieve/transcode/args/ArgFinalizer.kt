package com.sieve.transcode.args

/**
 * Post-build options applied on top of [FfmpegArgs.build], resolved from user/queue state.
 *
 * [requestedThreads] is already resolved by the caller: `userCpuThreads > 0 ? userCpuThreads
 * : max(1, cores - 2)`. [emitThreads] mirrors the desktop gate `effectiveEncoder == 'cpu' ||
 * useCpuFallback` — hardware (MediaCodec) encoding leaves it false. [audioGainDb] is expected
 * to arrive already clamped to `[-24, 24]` by the store setter.
 */
data class FinalizeOptions(
    val requestedThreads: Int,
    val emitThreads: Boolean,
    val burnSubtitles: Boolean = false,
    /** subtitlePath ?: inputPath — the source ffmpeg reads subtitles from when burning. */
    val subtitleSource: String? = null,
    val crfOverride: Int? = null,
    val normalizeAudio: Boolean = false,
    val audioGainDb: Int = 0,
    val rawArgs: String = "",
)

/**
 * Byte-exact port of the desktop `runSingleTranscode` arg-finalization pipeline.
 *
 * Order is exact and load-bearing: threads → burn-subs → CRF override → loudnorm → gain → raw.
 * Subs and loudnorm/gain mutate `-vf`/`-af` in place (append to an existing filter, or push a
 * fresh flag), so the sequence in which they run determines the final filter chain.
 */
object ArgFinalizer {

    fun finalize(base: List<String>, opts: FinalizeOptions): List<String> {
        val args = base.toMutableList()

        // 1. Threads — only for CPU/software encoding, and only when the cap allows a positive value.
        val threads = ThreadCaps.capThreadsForEncoder(args, opts.requestedThreads)
        if (opts.emitThreads && threads > 0) {
            args += "-threads"
            args += threads.toString()
        }

        // 2. Burn subtitles — escape backslashes→/, then colons, then quotes; append to -vf or push new.
        if (opts.burnSubtitles && !opts.subtitleSource.isNullOrEmpty()) {
            val escaped = opts.subtitleSource
                .replace("\\", "/")
                .replace(":", "\\:")
                .replace("'", "\\'")
            appendFilter(args, "-vf", "subtitles='$escaped'")
        }

        // 3. CRF override — replace the value after -crf, if present (no-op for bitrate/audio/image presets).
        if (opts.crfOverride != null) {
            val idx = args.indexOf("-crf")
            if (idx >= 0 && idx + 1 < args.size) args[idx + 1] = opts.crfOverride.toString()
        }

        // 4. Normalize audio (EBU R128) — append to -af or push new.
        if (opts.normalizeAudio) appendFilter(args, "-af", "loudnorm=I=-16:TP=-1.5:LRA=11")

        // 5. Audio gain — append after loudnorm if present, else push new -af.
        if (opts.audioGainDb != 0) {
            val db = opts.audioGainDb.coerceIn(-24, 24) // defensive; store setter is the real clamp
            appendFilter(args, "-af", "volume=${db}dB")
        }

        // 6. Raw args — split on whitespace, appended at the very end.
        if (opts.rawArgs.isNotBlank()) {
            args += opts.rawArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        }

        return args
    }

    /** Append `filter` to an existing `flag` value (comma-joined), or push `flag filter` if absent. */
    private fun appendFilter(args: MutableList<String>, flag: String, filter: String) {
        val idx = args.indexOf(flag)
        if (idx >= 0 && idx + 1 < args.size) {
            args[idx + 1] = args[idx + 1] + "," + filter
        } else {
            args += flag
            args += filter
        }
    }
}
