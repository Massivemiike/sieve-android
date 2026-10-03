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
 * The ladder is keyed on the SHORT side of the frame the encoder will actually receive (a 1280x720 and a
 * 720x1280 frame cost the same bits, so "720p" is one tier for both orientations): the preset's
 * [ScaleFilter.shortSide] tier capped by the source's own short side (that filter never upscales, so a 320x240
 * clip sent to a 720p preset is a 240-class encode), a literal `scale=W:H` target, else the source's short side.
 * A 4K clip sent to a 720p preset gets the 720p-class bitrate, not the 4K one.
 */
object MediaCodecSanitizer {

    /** Short side → base kbps for an H.264 encode at "CRF 23"-equivalent quality (30fps assumption). */
    private val H264_BASE_KBPS = listOf(
        2160 to 20000, 1440 to 10000, 1080 to 6000, 720 to 3500, 480 to 1800, 360 to 1200,
    )
    private const val FLOOR_KBPS = 300
    private const val CEIL_KBPS = 50000
    private const val DEFAULT_KBPS = 800 // below-360p / unknown-height fallback

    /**
     * [sourceHeight] / [sourceWidth] are the probed frame size of the source (null or 0 = unknown; rotation does not matter,
     * only the short side is used). Without [sourceWidth] the source is taken to be landscape, its height its short side.
     */
    fun sanitize(args: List<String>, sourceHeight: Int?, sourceWidth: Int? = null): List<String> {
        val encoder = valueAfter(args, "-c:v") ?: return args
        if (!encoder.endsWith("_mediacodec")) return args

        val crf = valueAfter(args, "-crf")?.toIntOrNull()
        var out = stripPair(args, "-crf")
        out = stripPair(out, "-preset")

        if ("-b:v" !in out) {
            val ladder = targetKbps(encoder, encodedShortSide(args, sourceWidth, sourceHeight), crf)
            val cap = valueAfter(out, "-maxrate")?.let(::parseKbps)
            out = out + listOf("-b:v", "${if (cap != null) minOf(ladder, cap) else ladder}k")
        }
        return out
    }

    /** Base ladder by the encoded frame's short side, scaled by the CRF intent (2^((23-crf)/6)), HEVC at 60% of H.264. */
    fun targetKbps(encoder: String, shortSide: Int?, crf: Int?): Int {
        val base = shortSide?.let { s -> H264_BASE_KBPS.firstOrNull { s >= it.first }?.second } ?: DEFAULT_KBPS
        val crfScale = if (crf != null) Math.pow(2.0, (23 - crf) / 6.0) else 1.0
        val codecScale = if (encoder.startsWith("hevc")) 0.6 else 1.0
        return (base * crfScale * codecScale).toInt().coerceIn(FLOOR_KBPS, CEIL_KBPS)
    }

    /** A literal `scale=W:H` (W may be -1/-2), at a filter boundary; `scale=1280:-2` or `scale=-2:ih/2` don't match. */
    private val SCALE_SIZE = Regex("(?:^|,)scale=([^:,]*):(\\d+)(?=[:,]|$)")

    /** The source's short side: the smaller of its two known dimensions; null when the height is unknown (an unknown width = landscape). */
    private fun sourceShortSide(width: Int?, height: Int?): Int? {
        val w = width?.takeIf { it > 0 }
        val h = height?.takeIf { it > 0 } ?: return null
        return if (w != null) minOf(w, h) else h
    }

    /**
     * Short side of the frame the encoder receives, or null when it is unknowable: the [ScaleFilter.shortSide] tier capped by
     * the source, else the LAST literal `scale=W:H` in `-vf` (its smaller side, a canvas like Instagram's 1080x1920 being a 1080-class
     * frame; a derived W, `-2`, leaves H), else the source's own short side (no scale: a "source" preset, or a width-driven one).
     */
    private fun encodedShortSide(args: List<String>, sourceWidth: Int?, sourceHeight: Int?): Int? {
        val source = sourceShortSide(sourceWidth, sourceHeight)
        val vf = valueAfter(args, "-vf") ?: return source
        ScaleFilter.shortSideTarget(vf)?.let { return ScaleFilter.shortSideAfter(it, source) }
        val literal = SCALE_SIZE.findAll(vf).lastOrNull() ?: return source
        val h = literal.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return source
        val w = literal.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
        return if (w != null) minOf(w, h) else h
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
