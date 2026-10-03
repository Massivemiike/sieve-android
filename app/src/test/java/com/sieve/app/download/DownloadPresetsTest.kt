package com.sieve.app.download

import com.sieve.app.ui.download.DownloadPresets
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The preset strings are the contract with yt-dlp, and a one-character change flips real downloads (a lower-resolution H.264
 * over a sharper VP9, a VBR "320", an .m4a "Opus"). They were proved against YouTube (landscape + Shorts), LinkedIn, SoundCloud,
 * Facebook (ten videos), Instagram, Vimeo, X and Dailymotion with yt-dlp 2026.08.19 + ffprobe; these tests lock exactly what was
 * proved.
 */
class DownloadPresetsTest {
    @Test fun presetIdsAreUniqueAndTheDefaultExists() {
        val ids = DownloadPresets.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(DownloadPresets.DEFAULT_ID, DownloadPresets.byId(DownloadPresets.DEFAULT_ID).id)
        assertEquals(DownloadPresets.DEFAULT_ID, DownloadPresets.byId("no-such-preset").id)
    }

    // ---- 1080p / 720p MP4: the best resolution within the short-side cap; H.264 only breaks ties ----

    private val fbHdRule = "b[format_id=hd][ext=mp4][url~='[?&]tag=(hd|dash_h264[a-z0-9_-]*_720p)(&|\$)']"

    @Test fun the720SelectorIsTheFacebookHdRuleThenAnyVideoThenAnyFile() {
        // No guard above 720 (nothing can be bigger than the Facebook hd and still inside a 720 cap), no codec filter.
        assertEquals("$fbHdRule/bv*+ba/b", DownloadPresets.mp4Format(720))
        assertEquals(DownloadPresets.mp4Format(720), DownloadPresets.byId("best-720").format)
    }

    @Test fun the1080SelectorPutsTwoBiggerThan720GuardsInFrontOfTheFacebookHdRule() {
        assertEquals(
            "bv*[width>720][width<=1080][height>1080]+ba/bv*[height>720][height<=1080][width>720]+ba/$fbHdRule/bv*+ba/b",
            DownloadPresets.mp4Format(1080),
        )
        assertEquals(DownloadPresets.mp4Format(1080), DownloadPresets.byId("best-1080").format)
    }

    @Test fun neitherMp4SelectorFiltersOnCodecSoH264CanNeverOutrankAHigherResolution() {
        // The first RC's `bv[vcodec~='^(avc|h264)']+ba/...` took the lower-resolution H.264 whenever there was any (a Facebook reel's
        // 362x640 sd over its 480x848 VP9, Shorts' 608x1080 H.264 over the 720x1280 VP9). H.264 is a sort key AFTER the resolution.
        for (id in listOf("best-1080", "best-720")) {
            val f = DownloadPresets.byId(id).format
            // (the Facebook hd rule names a CDN tag, `dash_h264...`, to rank a file yt-dlp cannot see: it is not a codec filter)
            val rest = f.replace(fbHdRule, "")
            for (word in listOf("vcodec", "acodec", "avc", "h264", "h265", "vp9", "av1")) assertFalse(word in rest, "$id mentions $word")
            val keys = Regex("""\[(\w+)[~=<>!]""").findAll(f).map { it.groupValues[1] }.toSet()
            assertTrue(keys.all { it in setOf("width", "height", "format_id", "ext", "url") }, "$id filters on $keys")
        }
    }

    /** What `[width>720]` style filters accept: a format of KNOWN size only (yt-dlp rejects a missing field). */
    private fun accepts(tier: String, width: Int?, height: Int?): Boolean = Regex("""\[(width|height)(>=|<=|>|<)(\d+)]""").findAll(tier).all { m ->
        val v = (if (m.groupValues[1] == "width") width else height) ?: return@all false
        val n = m.groupValues[3].toInt()
        when (m.groupValues[2]) { ">" -> v > n; "<" -> v < n; ">=" -> v >= n; else -> v <= n }
    }

    @Test fun theTwoGuardsAreDisjointAndTogetherExactlyShortSideAbove720UpToTheCap() {
        val sides = listOf(null, 1, 144, 480, 640, 719, 720, 721, 810, 900, 1079, 1080, 1081, 1280, 1440, 1920, 2160, 3840)
        for (cap in listOf(1080, 900, 1440)) {
            val tiers = DownloadPresets.mp4Format(cap).split('/').filter { "[width>" in it || "[height>" in it }
                .filter { "format_id" !in it }
            assertEquals(2, tiers.size, "cap $cap")
            for (w in sides) for (h in sides) {
                val hits = tiers.count { accepts(it, w, h) }
                val shortSide = if (w == null || h == null) null else minOf(w, h)
                val inRange = shortSide != null && shortSide > 720 && shortSide <= cap
                assertEquals(inRange, hits > 0, "${w}x$h under a $cap cap")
                assertTrue(hits <= 1, "${w}x$h matched both guards")
            }
        }
    }

    @Test fun aCapBelowTheFacebookHdSizeIsRefused() {
        assertFailsWith<IllegalArgumentException> { DownloadPresets.mp4Format(480) }
        assertFailsWith<IllegalArgumentException> { DownloadPresets.mp4Format(719) }
        assertTrue(DownloadPresets.mp4Format(720).isNotEmpty()) // 720 itself is the smallest cap the rule is valid for
    }

    /**
     * The tag Facebook's CDN puts last in a progressive stream's URL, and what ffprobe found in the file behind it. Only a tag that
     * is H.264 AND 720p is ranked as the 720p H.264 it is; every other tag is just an unknown-size file.
     */
    private val probedFacebookHdTags = mapOf(
        "hd" to "h264 1280x720", // an old video (uploaded about twelve years ago)
        "dash_h264-basic-gen2_720p" to "h264 720x1280", // beside VP9 DASH video up to 720x1280
        "compressed_source" to "vp9", // reel 480x848, videos 1080x1080 and 1080x1920
        "av1_compressed_source" to "av1", // 1920x1080 (a 75-minute video)
    )

    /** yt-dlp's `[url~='...']` is `re.search`; Kotlin's `containsMatchIn` is the same for this regex's plain syntax. */
    private fun hdTagRegex(): Regex = Regex(DownloadPresets.FB_H264_720P_HD_TAG)

    @Test fun theFacebookHdRuleIsInTheChainAndUsesTheTagRegex() {
        for (id in listOf("best-1080", "best-720")) {
            assertTrue("[url~='${DownloadPresets.FB_H264_720P_HD_TAG}']" in DownloadPresets.byId(id).format, id)
        }
    }

    @Test fun onlyAFacebookHdWhoseTagSaysH264At720pIsRankedAsOne() {
        val re = hdTagRegex()
        for ((tag, probed) in probedFacebookHdTags) {
            val url = "https://video-sjc6-1.xx.fbcdn.net/o1/v/t2/f2/m69/x.mp4?_nc_cat=1&efg=eyJ2ZW5jb2RlX3RhZyI6Ij&oh=00_A&oe=6A1&bitrate=123&tag=$tag"
            assertEquals(probed.startsWith("h264"), re.containsMatchIn(url), "tag=$tag is $probed")
        }
        // the tag may also come first or in the middle of the query
        assertTrue(re.containsMatchIn("https://x/y.mp4?tag=hd"))
        assertTrue(re.containsMatchIn("https://x/y.mp4?a=1&tag=hd&b=2"))
        assertTrue(re.containsMatchIn("https://x/y.mp4?a=1&tag=dash_h264-basic-gen2_720p&b=2"))
    }

    @Test fun anUnknownOrUnprovenFacebookTagIsNeverRankedAs720pH264() {
        val re = hdTagRegex()
        // Not proven H.264 at 720p: it competes as an unknown-size file (below every DASH format yt-dlp can size), never above one.
        val tags = listOf(
            "sd", "sve_sd", "hdr", "hd_vp9", "HD", "dash_vp9-basic-gen2_720p", "dash_r2av1-r1gen2vp9_q20", "", "dash_h265",
            "dash_h264-basic-gen2_1080p", "dash_h264-basic-gen2_480p", "dash_h264-basic-gen2", "dash_h264-basic-gen2_720p_x",
        )
        for (tag in tags) assertFalse(re.containsMatchIn("https://x/y.mp4?a=1&tag=$tag"), "tag=$tag")
        assertFalse(re.containsMatchIn("https://x/y.mp4?a=1&xtag=hd")) // another parameter that merely ends in "tag"
        assertFalse(re.containsMatchIn("https://x/hd/tag=hd/y.mp4")) // not in the query
        assertFalse(re.containsMatchIn("https://example.com/video.mp4")) // a site with no tag at all
    }

    @Test fun theSortRanksTheSmallestSideFirstAndH264OnlyBreaksTiesAtThatResolution() {
        for ((id, limit) in listOf("best-1080" to 1080, "best-720" to 720)) {
            val p = DownloadPresets.byId(id)
            val sort = p.extraArgs[p.extraArgs.indexOf("-S") + 1].split(',')
            assertEquals("res:$limit", sort.first(), id) // yt-dlp's `res` is min(width, height): a soft cap, the best one at or under N first
            // H.264 comes AFTER the resolution: the owner's "keep resolution" decision. The first RC had it the other way round.
            assertTrue(sort.indexOf("vcodec:h264") > sort.indexOf("res:$limit"), id)
            assertEquals(listOf("res:$limit", "vcodec:h264", "acodec:aac", "proto", "ext:mp4:m4a"), sort, id)
            // https before HLS ahead of the container: a VP9 .webm over https must beat the same VP9 as an HLS .mp4
            assertTrue(sort.indexOf("proto") < sort.indexOf("ext:mp4:m4a"), id)
        }
        assertEquals(DownloadPresets.mp4Args(720), DownloadPresets.byId("best-720").extraArgs)
        assertEquals(DownloadPresets.mp4Args(1080), DownloadPresets.byId("best-1080").extraArgs)
    }

    @Test fun anMp4PresetMergesIntoAnMp4EvenWhenTheBestVideoIsVp9OrAv1() {
        // A VP9 / AV1 video + AAC audio is a MERGE of two streams and comes out as .mp4 (copied, never re-encoded). A single-file
        // source in another container (a lone muxed .webm on a rare host) keeps its own: --merge-output-format does not touch it,
        // and --remux-video mp4 would make the whole download fail when the codecs cannot go into an mp4 (Vorbis), so it is
        // deliberately absent.
        for (id in listOf("best-1080", "best-720")) {
            val args = DownloadPresets.byId(id).extraArgs
            assertEquals("mp4", args[args.indexOf("--merge-output-format") + 1], id)
            assertFalse("--remux-video" in args, id)
            assertFalse(args.any { it == "-c:v" || it.startsWith("--recode") || it == "--postprocessor-args" }, id)
        }
    }

    @Test fun theMp4DescriptionsPromiseOnlyWhatThePresetsDo() {
        // Short on purpose (a half-width card shows one line). "Sharpest up to 1080p": the cap is a ceiling, not a promise of 1080
        // lines, and the codec is not promised at all (H.264 only wins a tie).
        assertEquals("Sharpest up to 1080p", DownloadPresets.byId("best-1080").desc)
        assertEquals("Up to 720p, smaller file", DownloadPresets.byId("best-720").desc)
        for (id in listOf("best-1080", "best-720")) assertFalse("H.264" in DownloadPresets.byId(id).desc, id)
    }

    // ---- MP3 320: a constant bitrate, not LAME V0 ----

    @Test fun theMp3PresetEncodesAConstant320() {
        val p = DownloadPresets.byId("audio-mp3")
        assertEquals("bestaudio/best", p.format)
        assertEquals(listOf("-x", "--audio-format", "mp3", "--audio-quality", "320K"), p.extraArgs)
        assertNotEquals("0", p.extraArgs.last()) // 0..10 select VBR (V0 is about 245 kbps); above 10 means -b:a Nk
        assertTrue(p.audioOnly)
    }

    // ---- Opus: always an .opus ----

    @Test fun theOpusPresetAlwaysProducesOpus() {
        val p = DownloadPresets.byId("audio-opus")
        // No `[ext=webm]`: it excluded Opus in other containers and sent every non-YouTube site down to the AAC .m4a.
        assertEquals("bestaudio/best", p.format)
        assertEquals(listOf("-x", "--audio-format", "opus", "--audio-quality", "128K"), p.extraArgs)
        assertTrue(p.audioOnly)
    }

    // ---- nothing else moved ----

    @Test fun theOtherPresetsAreExactlyWhatTheyWere() {
        val best = DownloadPresets.byId("best-video")
        assertEquals("bestvideo*+bestaudio/best", best.format)
        assertTrue(best.extraArgs.isEmpty())
        val fourK = DownloadPresets.byId("best-4k")
        assertEquals("bestvideo[height<=2160]+bestaudio/best", fourK.format)
        assertTrue(fourK.extraArgs.isEmpty())
        val audio = DownloadPresets.byId("audio-best")
        assertEquals("bestaudio/best", audio.format)
        assertEquals(listOf("-x"), audio.extraArgs)
        val archive = DownloadPresets.byId("archive")
        assertEquals("bestvideo+bestaudio/best", archive.format)
        assertEquals(
            listOf("--embed-subs", "--all-subs", "--embed-chapters", "--write-info-json", "--remux-video", "mkv"),
            archive.extraArgs,
        )
    }

    @Test fun onlyTheMp4PresetsCarryASortSoItCannotLeakIntoOtherPresets() {
        for (p in DownloadPresets.ALL) {
            assertEquals(p.id in setOf("best-1080", "best-720"), "-S" in p.extraArgs, p.id)
        }
    }
}
