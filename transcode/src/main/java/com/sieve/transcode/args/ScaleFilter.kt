package com.sieve.transcode.args

/**
 * The one place a preset's `scale` filter is built, read back and (for rows saved by an older build) repaired.
 *
 * A preset's resolution ("720p", "1080p", "4K") is a quality tier, not a promise of that frame size. So [shortSide]:
 *  - treats the number as the SHORT side of the frame: "720p" is 1280x720 for landscape and 720x1280 for portrait.
 *    The old `scale=-2:720` pinned the HEIGHT, which shrank a 1080x1920 portrait clip to 405x720;
 *  - never upscales: a source whose short side is already at or under the tier keeps its size. The old filter turned a
 *    320x240 clip into 960x720 (474 KB into 9.5 MB) and a 272x480 portrait clip into 612x1080. Only an odd dimension
 *    is trimmed by one pixel, because the encoders need even frames;
 *  - scales a bigger source so its short side is the tier, the long side following the aspect ratio rounded to even (ffmpeg's `-2`).
 *
 * The decision is an ffmpeg expression over `iw`/`ih`, not numbers read from a probe: ffmpeg evaluates it on the frames it
 * really decodes (rotation metadata already applied), so a probe that fails or reports the unrotated size cannot make it wrong.
 * The expression is single-quoted because it holds commas; ffmpeg's filtergraph parser unquotes it, so it survives
 * [ArgFinalizer] appending `,subtitles=...` after it, and it has no `;` (ArgFinalizer's complex-graph discriminator).
 *
 * Fixed-canvas presets (`ig-vert`, `ig-square`, `dvd-ntsc`, `dvd-pal`) deliberately do not use this: their output size IS
 * the format (a DVD muxer rejects any other frame size), so they keep their literal `scale=W:H`.
 */
object ScaleFilter {

    /** A preset tier (720, 1080, ...) as a never-upscaling short-side `scale` filter. The tier is even, so the scaled side is too. */
    fun shortSide(target: Int): String {
        require(target > 0 && target % 2 == 0) { "short-side target must be a positive even number, was $target" }
        val keep = "lte(min(iw,ih),$target)"
        // w: keep (trimmed to even) | landscape: derived (-2) | portrait or square: the tier. h: the mirror image.
        return "scale='if($keep,trunc(iw/2)*2,if(gt(iw,ih),-2,$target))':'if($keep,trunc(ih/2)*2,if(gt(iw,ih),$target,-2))'"
    }

    /**
     * A width cap (the GIF preset's 480): the width is [width] or the source's own when that is narrower, the height follows the aspect
     * ratio (`-1`: a GIF has no even-size rule). [options] is appended as is, e.g. `":flags=lanczos"`.
     */
    fun maxWidth(width: Int, options: String = ""): String = "scale='min($width,iw)':-1$options"

    /** Opens a [shortSide] filter, at a filter boundary; the tier is captured. */
    private val SHORT_SIDE_HEAD = Regex("(?:^|,)scale='if\\(lte\\(min\\(iw,ih\\),(\\d+)\\)")

    /** The tier of the LAST [shortSide] filter in a `-vf` chain, or null when it has none (a look-alike that differs anywhere does not count). */
    fun shortSideTarget(vf: String): Int? = SHORT_SIDE_HEAD.findAll(vf)
        .mapNotNull { m -> m.groupValues[1].toIntOrNull()?.takeIf { it > 0 && it % 2 == 0 && shortSide(it) in vf } }
        .lastOrNull()

    /**
     * The short side of the frame a [shortSide]`(target)` filter yields from a source whose short side is [sourceShortSide]
     * (null or not positive = unknown, which is bounded by the tier): the source's own, trimmed to even, when it is at or
     * under the tier, else the tier. This is what the bitrate ladder is keyed on.
     */
    fun shortSideAfter(target: Int, sourceShortSide: Int?): Int = when {
        sourceShortSide == null || sourceShortSide <= 0 -> target
        sourceShortSide <= target -> sourceShortSide / 2 * 2
        else -> target
    }

    // The filters the presets used before 1.0.4. Rows saved by that build keep these in their persisted args.
    private val LEGACY_TIER = Regex("(^|,)scale=-2:(\\d+)(?=,|$)")
    private const val LEGACY_GIF = "scale=480:-1:flags=lanczos"

    /**
     * Repairs the args of a transcode saved before 1.0.4 (a retried row, one restored after the update): its literal
     * `scale=-2:H` becomes [shortSide] and the GIF's `scale=480:-1:flags=lanczos` becomes [maxWidth], so it too neither upscales nor
     * shrinks a portrait clip. Args already in the new form, and every other `-vf`, come back untouched.
     */
    fun upgradeLegacy(args: List<String>): List<String> {
        val at = args.indexOf("-vf")
        if (at < 0 || at + 1 >= args.size) return args
        val vf = args[at + 1]
        val upgraded = vf
            .replace(LEGACY_TIER) { m ->
                val tier = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 && it % 2 == 0 }
                if (tier == null) m.value else m.groupValues[1] + shortSide(tier)
            }
            .replace(LEGACY_GIF, maxWidth(480, ":flags=lanczos"))
        if (upgraded == vf) return args
        return args.toMutableList().also { it[at + 1] = upgraded }
    }
}
