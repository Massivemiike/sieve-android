package com.sieve.transcode.args

/**
 * Spawn-time post-pass over a preset's arg vector: restore the SOURCE sample rate after loudnorm.
 * Applied ONLY at spawn time (the persisted arg vector keeps `aresample=48000`, byte-exact with the
 * desktop app); a no-op unless the `-af` chain carries `loudnorm=` + `aresample=48000`.
 *
 * Why: loudnorm upsamples to 192 kHz internally, so [ArgFinalizer] appends `aresample=48000` to
 * bring the stream back down. Desktop then probes the source and, when its audio sample rate is
 * sane, swaps in the source's own rate so a 44.1 kHz file stays 44.1 kHz. Android has no ffprobe,
 * so the rate comes from `MediaExtractor` via
 * [com.sieve.transcode.runner.android.SourceProbe]; an unreadable rate (null) keeps 48 kHz.
 */
object LoudnormRate {

    private const val DEFAULT_TOKEN = "aresample=48000"
    private val VALID_RATES = 8000..192000

    fun restore(args: List<String>, sampleRate: Int?): List<String> {
        if (sampleRate == null || sampleRate !in VALID_RATES) return args
        val idx = args.indexOf("-af")
        if (idx < 0 || idx + 1 >= args.size) return args
        val chain = args[idx + 1]
        if (!chain.contains("loudnorm=") || !chain.contains(DEFAULT_TOKEN)) return args

        val out = args.toMutableList()
        out[idx + 1] = chain.replaceFirst(DEFAULT_TOKEN, "aresample=$sampleRate") // first match only, like desktop's JS replace
        return out
    }
}
