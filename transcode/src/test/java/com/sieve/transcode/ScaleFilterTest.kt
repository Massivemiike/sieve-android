package com.sieve.transcode

import com.sieve.transcode.args.ArgFinalizer
import com.sieve.transcode.args.FinalizeOptions
import com.sieve.transcode.args.ScaleFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.min

/**
 * What ffmpeg 7/8's `scale` filter makes of [filter] for an [iw] x [ih] frame (the frame AFTER rotation metadata is applied,
 * which is what `iw`/`ih` are in a `-vf` chain). It evaluates the emitted text itself, so these tests exercise the string a
 * preset really spawns with rather than a Kotlin copy of what it is meant to do: the expression subset the presets use
 * (`if lte lt gt min max trunc` and arithmetic over `iw`/`ih`), then ffmpeg's rule for a negative side (`-1`/`-n`: derived from
 * the other side, aspect preserved, to the nearest multiple of n, a tie going up). The expected sizes in this file were
 * captured from real ffmpeg 8.0.1 (the app bundles 7.1.1, the same evaluator) by decoding generated sources through the filter.
 */
internal fun ffmpegScale(filter: String, iw: Int, ih: Int): Pair<Int, Int> {
    require(filter.startsWith("scale=")) { "not a scale filter: $filter" }
    // The positional options (w, h), then k=v ones (flags=...); ':' inside single quotes belongs to the expression.
    val options = ArrayList<String>()
    val cur = StringBuilder()
    var quoted = false
    for (c in filter.removePrefix("scale=")) {
        when {
            c == '\'' -> { quoted = !quoted; cur.append(c) }
            c == ':' && !quoted -> { options += cur.toString(); cur.clear() }
            else -> cur.append(c)
        }
    }
    options += cur.toString()
    val (wExpr, hExpr) = options.take(2).map { it.trim('\'') }
    val vars = mapOf("iw" to iw.toDouble(), "ih" to ih.toDouble())
    var w = ExprParser(wExpr, vars).parse().toInt()
    var h = ExprParser(hExpr, vars).parse().toInt()
    require(!(w < 0 && h < 0)) { "both sides derived: $filter" }
    fun nearestMultiple(x: Double, n: Int) = (floor(x / n + 0.5) * n).toInt()
    if (w < 0) w = nearestMultiple(h.toDouble() * iw / ih, if (w < -1) -w else 1)
    else if (h < 0) h = nearestMultiple(w.toDouble() * ih / iw, if (h < -1) -h else 1)
    return w to h
}

/** The `scale=...` filter inside a `-vf` chain (up to the next comma or semicolon outside single quotes), or null when it has none. */
internal fun scaleFilterOf(vf: String): String? {
    val start = vf.indexOf("scale=").takeIf { it >= 0 } ?: return null
    var quoted = false
    for (i in start until vf.length) {
        val c = vf[i]
        if (c == '\'') quoted = !quoted
        else if (!quoted && (c == ',' || c == ';')) return vf.substring(start, i)
    }
    return vf.substring(start)
}

/** The slice of ffmpeg's expression language [ffmpegScale] needs. A function call's arguments are comma separated. */
private class ExprParser(private val s: String, private val vars: Map<String, Double>) {
    private var i = 0

    fun parse(): Double = sum().also { require(i == s.length) { "unparsed '${s.substring(i)}' in $s" } }

    private fun sum(): Double {
        var v = product()
        while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = product(); v = if (op == '+') v + r else v - r }
        return v
    }

    private fun product(): Double {
        var v = unary()
        while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val r = unary(); v = if (op == '*') v * r else v / r }
        return v
    }

    private fun unary(): Double = if (s[i] == '-') { i++; -unary() } else primary()

    private fun primary(): Double {
        if (s[i] == '(') { i++; return sum().also { expect(')') } }
        val start = i
        if (s[i].isDigit() || s[i] == '.') {
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()
        }
        while (i < s.length && s[i].isLetterOrDigit()) i++
        val name = s.substring(start, i)
        require(name.isNotEmpty()) { "unexpected '${s[i]}' at $i in $s" }
        if (i == s.length || s[i] != '(') return vars[name] ?: error("unknown variable $name in $s")
        i++
        val args = ArrayList<Double>()
        while (true) {
            args += sum()
            if (s[i] == ',') i++ else break
        }
        expect(')')
        fun flag(b: Boolean) = if (b) 1.0 else 0.0
        return when (name) {
            "if" -> if (args[0] != 0.0) args[1] else args[2]
            "lt" -> flag(args[0] < args[1])
            "lte" -> flag(args[0] <= args[1])
            "gt" -> flag(args[0] > args[1])
            "gte" -> flag(args[0] >= args[1])
            "min" -> min(args[0], args[1])
            "max" -> maxOf(args[0], args[1])
            "trunc" -> args[0].toLong().toDouble()
            else -> error("unsupported function $name in $s")
        }
    }

    private fun expect(c: Char) { require(i < s.length && s[i] == c) { "expected '$c' at $i in $s" }; i++ }
}

class ScaleFilterTest {

    private fun tier(target: Int, w: Int, h: Int) = ffmpegScale(ScaleFilter.shortSide(target), w, h)

    // ── the evaluator itself: the numbers it must reproduce are what real ffmpeg does ──
    @Test fun `the evaluator reproduces the old filter's upscale, so the cases below are not vacuous`() {
        // scale=-2:720 on a 320x240 clip is the 960x720 the owner's phone produced (474 KB -> 9.5 MB)
        assertEquals(960 to 720, ffmpegScale("scale=-2:720", 320, 240))
        // and scale=-2:1080 on the 272x480 portrait clip is the 612x1080 one
        assertEquals(612 to 1080, ffmpegScale("scale=-2:1080", 272, 480))
        // and "720p" on a 1080x1920 portrait clip was a 406x720 sliver (405 rounds to the next even), not 720x1280
        assertEquals(406 to 720, ffmpegScale("scale=-2:720", 1080, 1920))
    }

    // ── landscape ──
    @Test fun `a landscape source above the tier is scaled so its short side is the tier`() {
        assertEquals(1280 to 720, tier(720, 1920, 1080))
        assertEquals(1920 to 1080, tier(1080, 3840, 2160))
        assertEquals(960 to 720, tier(720, 4000, 3000))   // 4:3
        assertEquals(1720 to 720, tier(720, 1920, 804))   // 2.39:1 - the long side follows the aspect ratio (1719.4 -> 1720)
    }

    @Test fun `a landscape source under the tier keeps its size - the 320x240 clip is not blown up to 960x720`() {
        assertEquals(320 to 240, tier(720, 320, 240))
        assertEquals(640 to 360, tier(1080, 640, 360))
        assertEquals(1280 to 720, tier(1080, 1280, 720)) // 720p into the 1080p tier stays 720p
        assertEquals(1920 to 1080, tier(2160, 1920, 1080))
    }

    // ── portrait ──
    @Test fun `a portrait source above the tier gets the tier as its SHORT side - 720p portrait is 720x1280, not 405x720`() {
        assertEquals(720 to 1280, tier(720, 1080, 1920))
        assertEquals(1080 to 1920, tier(1080, 2160, 3840))
        assertEquals(1440 to 2560, tier(1440, 2160, 3840))
        assertEquals(720 to 960, tier(720, 3000, 4000))   // 3:4
    }

    @Test fun `a portrait source under the tier keeps its size - 272x480 is not blown up to 612x1080`() {
        assertEquals(272 to 480, tier(1080, 272, 480))
        assertEquals(480 to 640, tier(720, 480, 640))
        assertEquals(720 to 1280, tier(1080, 720, 1280))
    }

    // ── square ──
    @Test fun `a square source is held to the tier on both sides and never upscaled`() {
        assertEquals(500 to 500, tier(720, 500, 500))
        assertEquals(720 to 720, tier(720, 720, 720))
        assertEquals(720 to 720, tier(720, 1000, 1000))
        assertEquals(1080 to 1080, tier(1080, 4320, 4320))
    }

    // ── odd sizes ──
    @Test fun `an odd source dimension is trimmed to even because the encoders need even frames`() {
        assertEquals(320 to 240, tier(720, 321, 241))   // under the tier: trimmed, not scaled
        assertEquals(240 to 320, tier(720, 241, 321))
        assertEquals(718 to 1280, tier(720, 719, 1280)) // a 719-wide portrait source is under the tier
        assertEquals(1280 to 718, tier(720, 1280, 719))
        assertEquals(498 to 498, tier(720, 499, 499))
    }

    @Test fun `an odd source above the tier scales to even and the derived side is the nearest even`() {
        assertEquals(720 to 1280, tier(720, 1081, 1921)) // 1279.5 -> 1280
        assertEquals(1920 to 1080, tier(1080, 1921, 1081))
        // an exact tie between two evens goes up, as ffmpeg's -2 does
        assertEquals(720 to 1282, tier(720, 1440, 2562)) // 1281.0
        assertEquals(1282 to 720, tier(720, 2562, 1440))
        assertEquals(1080 to 1924, tier(1080, 2160, 3849)) // 1924.5
    }

    @Test fun `a source exactly at the tier is left alone`() {
        assertEquals(1280 to 720, tier(720, 1280, 720))
        assertEquals(720 to 1280, tier(720, 720, 1280))
        assertEquals(1920 to 1080, tier(1080, 1921, 1080)) // short side at the tier, long side odd: trimmed
    }

    // ── properties over a grid of sources ──
    private val sources: List<Pair<Int, Int>> = buildList {
        val sizes = listOf(2, 100, 239, 240, 321, 480, 481, 719, 720, 721, 1080, 1081, 1279, 1280, 1281, 1920, 2161, 2160, 3840, 4001)
        for (w in sizes) for (h in sizes) add(w to h)
    }
    private val tiers = listOf(720, 1080, 1440, 2160)

    @Test fun `for every source and tier the output is even, never larger than the source and never above the tier on its short side`() {
        for ((w, h) in sources) for (t in tiers) {
            val (ow, oh) = tier(t, w, h)
            val what = "${w}x$h at ${t}p -> ${ow}x$oh"
            assertTrue("$what: odd side", ow % 2 == 0 && oh % 2 == 0)
            assertTrue("$what: empty frame", ow >= 2 && oh >= 2)
            assertTrue("$what: wider than the source", ow <= w)
            assertTrue("$what: taller than the source", oh <= h)
            assertTrue("$what: short side above the tier", min(ow, oh) <= t)
            // a landscape source stays landscape and a portrait one portrait (equal only when the source is square or trimmed to it)
            if (w > h) assertTrue("$what: orientation flipped", ow >= oh)
            if (w < h) assertTrue("$what: orientation flipped", ow <= oh)
        }
    }

    @Test fun `a source above the tier ends up exactly on it - the tier is the short side, not a ceiling that undershoots`() {
        for ((w, h) in sources) for (t in tiers) {
            if (min(w, h) <= t) continue
            val (ow, oh) = tier(t, w, h)
            assertEquals("${w}x$h at ${t}p -> ${ow}x$oh", t, min(ow, oh))
        }
    }

    @Test fun `a source at or under the tier is only ever trimmed to even - the old filter's upscale is gone`() {
        for ((w, h) in sources) for (t in tiers) {
            if (min(w, h) > t) continue
            assertEquals("${w}x$h at ${t}p", (w / 2 * 2) to (h / 2 * 2), tier(t, w, h))
        }
    }

    // ── what the bitrate ladder is keyed on ──
    @Test fun `shortSideAfter is the short side the filter really produces`() {
        for ((w, h) in sources) for (t in tiers) {
            val (ow, oh) = tier(t, w, h)
            assertEquals("${w}x$h at ${t}p", min(ow, oh), ScaleFilter.shortSideAfter(t, min(w, h)))
        }
    }

    @Test fun `shortSideAfter without a known source is bounded by the tier`() {
        assertEquals(720, ScaleFilter.shortSideAfter(720, null))
        assertEquals(720, ScaleFilter.shortSideAfter(720, 0))
        assertEquals(240, ScaleFilter.shortSideAfter(720, 240))
        assertEquals(720, ScaleFilter.shortSideAfter(720, 2160))
    }

    // ── reading it back ──
    @Test fun `shortSideTarget reads back the tier of a filter built by shortSide`() {
        for (t in tiers) assertEquals(t, ScaleFilter.shortSideTarget(ScaleFilter.shortSide(t)))
        assertEquals(720, ScaleFilter.shortSideTarget("fps=30," + ScaleFilter.shortSide(720) + ",subtitles='a.srt'"))
        // the last one is the one the encoder gets
        assertEquals(480, ScaleFilter.shortSideTarget(ScaleFilter.shortSide(720) + "," + ScaleFilter.shortSide(480)))
    }

    @Test fun `shortSideTarget ignores everything that is not exactly the short-side filter`() {
        assertNull(ScaleFilter.shortSideTarget("scale=-2:720"))
        assertNull(ScaleFilter.shortSideTarget("scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2"))
        assertNull(ScaleFilter.shortSideTarget("subtitles='a.srt'"))
        assertNull(ScaleFilter.shortSideTarget("scale='min(480,iw)':-1:flags=lanczos"))
        // a look-alike with a different body is not trusted to mean "short side 720"
        assertNull(ScaleFilter.shortSideTarget(ScaleFilter.shortSide(720).replace("trunc(iw/2)*2", "iw")))
        // an odd tier cannot be one of ours
        assertNull(ScaleFilter.shortSideTarget("scale='if(lte(min(iw,ih),721),1,2)':'3'"))
    }

    @Test fun `the tier must be a positive even number`() {
        assertThrows(IllegalArgumentException::class.java) { ScaleFilter.shortSide(0) }
        assertThrows(IllegalArgumentException::class.java) { ScaleFilter.shortSide(-720) }
        assertThrows(IllegalArgumentException::class.java) { ScaleFilter.shortSide(721) }
    }

    // ── the text has to survive the rest of the arg pipeline ──
    @Test fun `the filter has no semicolon - ArgFinalizer treats one as a complex graph and refuses to burn subtitles into it`() {
        assertFalse(';' in ScaleFilter.shortSide(720))
        val args = ArgFinalizer.finalize(
            listOf("-c:v", "libx264", "-vf", ScaleFilter.shortSide(720)),
            FinalizeOptions(requestedThreads = 4, emitThreads = false, burnSubtitles = true, subtitleSource = "/in/a.srt"),
        )
        // burned in, after the scale, in one comma chain
        assertEquals(ScaleFilter.shortSide(720) + ",subtitles='/in/a.srt'", args[args.indexOf("-vf") + 1])
    }

    @Test fun `the quotes balance, so the commas inside the expressions never split the filter chain`() {
        val f = ScaleFilter.shortSide(1080)
        assertEquals(0, f.count { it == '\'' } % 2)
        // outside the quotes there is exactly one separator, between the two options
        var quoted = false
        val outside = StringBuilder()
        for (c in f) if (c == '\'') quoted = !quoted else if (!quoted) outside.append(c)
        assertEquals("scale=:", outside.toString())
    }

    // ── the GIF's width cap ──
    @Test fun `maxWidth keeps a narrower source as it is and shrinks a wider one to the cap`() {
        val gif = ScaleFilter.maxWidth(480, ":flags=lanczos")
        assertEquals("scale='min(480,iw)':-1:flags=lanczos", gif)
        assertEquals(320 to 240, ffmpegScale(gif, 320, 240))   // the old scale=480:-1 made this 480x360
        assertEquals(272 to 480, ffmpegScale(gif, 272, 480))
        assertEquals(480 to 270, ffmpegScale(gif, 480, 270))
        assertEquals(480 to 270, ffmpegScale(gif, 1920, 1080))
        assertEquals(480 to 853, ffmpegScale(gif, 1080, 1920))
        assertEquals("scale='min(100,iw)':-1", ScaleFilter.maxWidth(100))
    }

    // ── rows saved before 1.0.4 ──
    @Test fun `upgradeLegacy rewrites an old preset's scale-2-H and keeps everything around it`() {
        val old = listOf("-c:v", "libx264", "-crf", "22", "-vf", "scale=-2:720", "-c:a", "aac")
        assertEquals(
            listOf("-c:v", "libx264", "-crf", "22", "-vf", ScaleFilter.shortSide(720), "-c:a", "aac"),
            ScaleFilter.upgradeLegacy(old),
        )
        assertEquals(
            "fps=30," + ScaleFilter.shortSide(1080) + ",subtitles='a.srt'",
            ScaleFilter.upgradeLegacy(listOf("-vf", "fps=30,scale=-2:1080,subtitles='a.srt'"))[1],
        )
        assertEquals(ScaleFilter.shortSide(2160), ScaleFilter.upgradeLegacy(listOf("-vf", "scale=-2:2160"))[1])
    }

    @Test fun `upgradeLegacy rewrites the old GIF width as a cap`() {
        val old = listOf("-vf", "fps=12,scale=480:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse", "-loop", "0")
        assertEquals(
            listOf("-vf", "fps=12,scale='min(480,iw)':-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse", "-loop", "0"),
            ScaleFilter.upgradeLegacy(old),
        )
    }

    @Test fun `upgradeLegacy leaves new args, fixed canvases, other scales and filterless args alone`() {
        val untouched = listOf(
            listOf("-vf", ScaleFilter.shortSide(720)),
            listOf("-vf", "scale='min(480,iw)':-1:flags=lanczos"),
            listOf("-vf", "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2"),
            listOf("-vf", "scale=720:480"),
            listOf("-vf", "scale=-2:ih/2"),
            listOf("-vf", "scale=-2:721"),            // an odd tier was never a preset
            listOf("-vf", "scale=-2:720:flags=lanczos"), // not the exact old form
            listOf("-vf", "subtitles='a.srt'"),
            listOf("-vn", "-c:a", "aac"),
            listOf("-c:v", "libx264", "-vf"),
            emptyList(),
        )
        for (args in untouched) assertSame("$args", args, ScaleFilter.upgradeLegacy(args))
    }

    @Test fun `upgradeLegacy is idempotent`() {
        val once = ScaleFilter.upgradeLegacy(listOf("-vf", "scale=-2:1080,subtitles='a.srt'"))
        assertSame(once, ScaleFilter.upgradeLegacy(once))
    }
}
