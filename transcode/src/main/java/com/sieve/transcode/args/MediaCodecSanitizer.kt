package com.sieve.transcode.args

/**
 * Post-pass over a preset's arg vector for MediaCodec hardware encodes. Applied ONLY at spawn time
 * (the persisted preset args stay byte-exact); a no-op for software encodes.
 *
 * Why: ffmpeg's `h264_mediacodec`/`hevc_mediacodec` encoders silently IGNORE `-crf` and `-preset`
 * (libx26x private options). Verified on a Galaxy S26: the encode "succeeds" but falls back to
 * ffmpeg's default ~200 kbps rate control — unwatchable output. So for a `*_mediacodec` video
 * encoder we strip the two ignored options and, when the preset carries no explicit `-b:v`, inject
 * one derived from the height the encoder will actually receive and the preset's CRF intent.
 *
 * `-maxrate`/`-bufsize` are NOT rate control for these wrappers either (they only consume
 * `AVCodecContext.bit_rate`), so a preset that carries just those (the 1440p/4K tiers) still needs the
 * injected `-b:v`; its `-maxrate` is honoured as a ceiling on that value.
 *
 * The ladder height is the preset's own `scale=W:H` target when it has a literal one, else the
 * source height — a 4K clip sent to a 720p preset gets the 720p-class bitrate, not the 4K one.
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

        if ("-b:v" !in out) {
            val ladder = targetKbps(encoder, outputHeight(args) ?: sourceHeight, crf)
            val cap = valueAfter(out, "-maxrate")?.let(::parseKbps)
            out = out + listOf("-b:v", "${if (cap != null) minOf(ladder, cap) else ladder}k")
        }
        return out
    }

    /** Base ladder by encoded frame height, scaled by the CRF intent (2^((23-crf)/6)), HEVC at 60% of H.264. */
    fun targetKbps(encoder: String, height: Int?, crf: Int?): Int {
        val base = height?.let { h -> H264_BASE_KBPS.firstOrNull { h >= it.first }?.second } ?: DEFAULT_KBPS
        val crfScale = if (crf != null) Math.pow(2.0, (23 - crf) / 6.0) else 1.0
        val codecScale = if (encoder.startsWith("hevc")) 0.6 else 1.0
        return (base * crfScale * codecScale).toInt().coerceIn(FLOOR_KBPS, CEIL_KBPS)
    }

    /** A literal `scale=W:H` (W may be -1/-2), at a filter boundary; `scale=1280:-2` or `scale=-2:ih/2` don't match. */
    private val SCALE_HEIGHT = Regex("(?:^|,)scale=[^:,]*:(\\d+)(?=[:,]|$)")

    /** Height the encoder receives: the LAST literal `scale=` target in `-vf`, or null when there isn't one. */
    private fun outputHeight(args: List<String>): Int? {
        val vf = valueAfter(args, "-vf") ?: return null
        return SCALE_HEIGHT.findAll(vf).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    /** An ffmpeg bitrate (`18M`, `2.5M`, `1500k`/`1500K`, or plain bits per second) in kbps; null if unparseable. */
    private fun parseKbps(s: String): Int? {
        val t = s.trim()
        val (num, perUnitKbps) = when {
            t.endsWith("M") -> t.dropLast(1) to 1000.0
            t.endsWith("k") || t.endsWith("K") -> t.dropLast(1) to 1.0
            else -> t to 0.001
        }
        return num.toDoubleOrNull()?.let { (it * perUnitKbps).toInt() }?.takeIf { it > 0 }
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
