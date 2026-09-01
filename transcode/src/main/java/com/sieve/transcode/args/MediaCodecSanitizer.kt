package com.sieve.transcode.args

/**
 * Post-pass over a preset's arg vector for MediaCodec hardware encodes. Applied ONLY at spawn time
 * (the persisted preset args stay byte-exact); a no-op for software encodes.
 *
 * Why: ffmpeg's `h264_mediacodec`/`hevc_mediacodec` encoders silently IGNORE `-crf` and `-preset`
 * (libx26x private options). Verified on a Galaxy S26: the encode "succeeds" but falls back to
 * ffmpeg's default ~200 kbps rate control — unwatchable output. So for a `*_mediacodec` video
 * encoder we strip the two ignored options and, when the preset carries no explicit rate control
 * (`-b:v`/`-maxrate`), inject `-b:v` derived from the source height and the preset's CRF intent.
 */
object MediaCodecSanitizer {

    /** Height → base kbps for an H.264 encode at "CRF 23"-equivalent quality (30fps assumption). */
    private val H264_BASE_KBPS = listOf(
        2160 to 20000, 1440 to 10000, 1080 to 6000, 720 to 3500, 480 to 1800, 360 to 1200,
    )
    private const val FLOOR_KBPS = 300
    private const val CEIL_KBPS = 50000
    private const val DEFAULT_KBPS = 800 // below-360p / unknown-height fallback

    fun sanitize(args: List<String>, sourceHeight: Int?): List<String> {
        val encoder = valueAfter(args, "-c:v") ?: return args
        if (!encoder.endsWith("_mediacodec")) return args

        val crf = valueAfter(args, "-crf")?.toIntOrNull()
        var out = stripPair(args, "-crf")
        out = stripPair(out, "-preset")

        if ("-b:v" !in out && "-maxrate" !in out) {
            out = out + listOf("-b:v", "${targetKbps(encoder, sourceHeight, crf)}k")
        }
        return out
    }

    /** Base ladder by height, scaled by the CRF intent (2^((23-crf)/6)), HEVC at 60% of H.264. */
    fun targetKbps(encoder: String, sourceHeight: Int?, crf: Int?): Int {
        val base = sourceHeight?.let { h -> H264_BASE_KBPS.firstOrNull { h >= it.first }?.second } ?: DEFAULT_KBPS
        val crfScale = if (crf != null) Math.pow(2.0, (23 - crf) / 6.0) else 1.0
        val codecScale = if (encoder.startsWith("hevc")) 0.6 else 1.0
        return (base * crfScale * codecScale).toInt().coerceIn(FLOOR_KBPS, CEIL_KBPS)
    }

    private fun valueAfter(args: List<String>, flag: String): String? {
        val i = args.indexOf(flag)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    private fun stripPair(args: List<String>, flag: String): List<String> {
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            if (args[i] == flag && i + 1 < args.size) i += 2 else { out += args[i]; i++ }
        }
        return out
    }
}
