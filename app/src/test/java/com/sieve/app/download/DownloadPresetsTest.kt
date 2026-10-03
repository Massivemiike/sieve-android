package com.sieve.app.download

import com.sieve.app.ui.download.DownloadPresets
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The preset strings are the contract with yt-dlp, and a one-character change flips real downloads (VP9 in an .mp4, a VBR
 * "320", an .m4a "Opus"). They were proved against YouTube (landscape + Shorts), LinkedIn, SoundCloud, Facebook, Instagram,
 * Vimeo, X and Dailymotion with yt-dlp 2026.08.19 + ffprobe; these tests lock exactly what was proved.
 */
class DownloadPresetsTest {
    private val h264 = "[vcodec~='^(avc|h264)']"

    @Test fun presetIdsAreUniqueAndTheDefaultExists() {
        val ids = DownloadPresets.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(DownloadPresets.DEFAULT_ID, DownloadPresets.byId(DownloadPresets.DEFAULT_ID).id)
        assertEquals(DownloadPresets.DEFAULT_ID, DownloadPresets.byId("no-such-preset").id)
    }

    // ---- 1080p / 720p MP4: H.264 first, sized by the SHORT side, never an unknown-codec VP9 ----

    @Test fun bothMp4PresetsShareOneH264FirstChainInThePromisedOrder() {
        val expected = listOf(
            "bv$h264+ba", // known H.264 video + separate audio (YouTube, Instagram, X, Vimeo)
            "b$h264", // a known-H.264 file that carries its own audio (Dailymotion)
            // Facebook's progressive hd, only when its CDN tag says H.264 (an old video's hd, never the VP9 re-encode)
            "b[format_id=hd][ext=mp4][url~='[?&]tag=(hd|dash_h264[a-z0-9_-]*)(&|\$)']",
            "b[format_id=sd][ext=mp4]", // Facebook: its progressive sd is H.264 on every video probed
            "bv*+ba", // no H.264 at all: any video + audio
            "b", // ...and anything, so a download never fails with "format not available"
        )
        for (id in listOf("best-1080", "best-720")) {
            assertEquals(expected, DownloadPresets.byId(id).format.split('/'), id)
            assertEquals(DownloadPresets.MP4_FORMAT, DownloadPresets.byId(id).format, id)
        }
    }

    /** The tag Facebook's CDN puts last in a progressive stream's URL, and what ffprobe found in the file behind it. */
    private val probedFacebookHdTags = mapOf(
        "hd" to "h264", // an old video (uploaded about twelve years ago): H.264 High 1280x720
        "dash_h264-basic-gen2_720p" to "h264", // H.264 High 720x1280, beside VP9 DASH video
        "compressed_source" to "vp9", // reel 480x848, video 1080x1080, video 1080x1920 (three videos)
        "av1_compressed_source" to "av1", // by its name (the 1080p+ file could not be probed from a partial download)
    )

    /** yt-dlp's `[url~='...']` is `re.search`; Kotlin's `containsMatchIn` is the same for this regex's plain syntax. */
    private fun hdTagRegex(): Regex = Regex(DownloadPresets.FB_H264_HD_TAG)

    @Test fun theFacebookHdRuleIsInTheChainAndUsesTheTagRegex() {
        for (id in listOf("best-1080", "best-720")) {
            assertTrue("[url~='${DownloadPresets.FB_H264_HD_TAG}']" in DownloadPresets.byId(id).format, id)
        }
    }

    @Test fun onlyAFacebookHdWhoseTagSaysH264IsTaken() {
        val re = hdTagRegex()
        for ((tag, codec) in probedFacebookHdTags) {
            val url = "https://video-sjc6-1.xx.fbcdn.net/o1/v/t2/f2/m69/x.mp4?_nc_cat=1&efg=eyJ2ZW5jb2RlX3RhZyI6Ij&oh=00_A&oe=6A1&bitrate=123&tag=$tag"
            assertEquals(codec == "h264", re.containsMatchIn(url), "tag=$tag is $codec")
        }
        // the tag may also come first or in the middle of the query
        assertTrue(re.containsMatchIn("https://x/y.mp4?tag=hd"))
        assertTrue(re.containsMatchIn("https://x/y.mp4?a=1&tag=hd&b=2"))
        assertTrue(re.containsMatchIn("https://x/y.mp4?a=1&tag=dash_h264-basic-gen2_1080p&b=2"))
    }

    @Test fun anUnknownOrLookAlikeFacebookTagFallsBackToSdNeverToAnUnprovenHd() {
        val re = hdTagRegex()
        for (tag in listOf("sd", "sve_sd", "hdr", "hd_vp9", "HD", "dash_vp9-basic-gen2_720p", "dash_r2av1-r1gen2vp9_q20", "", "dash_h265")) {
            assertFalse(re.containsMatchIn("https://x/y.mp4?a=1&tag=$tag"), "tag=$tag")
        }
        assertFalse(re.containsMatchIn("https://x/y.mp4?a=1&xtag=hd")) // another parameter that merely ends in "tag"
        assertFalse(re.containsMatchIn("https://x/hd/tag=hd/y.mp4")) // not in the query
        assertFalse(re.containsMatchIn("https://example.com/video.mp4")) // a site with no tag at all
    }

    @Test fun theMp4SizeCapIsTheShortSideNotTheHeight() {
        for ((id, limit) in listOf("best-1080" to 1080, "best-720" to 720)) {
            val p = DownloadPresets.byId(id)
            // `[height<=N]` dropped a vertical video (480x848 is "480p") and every format of unknown height.
            assertFalse("height" in p.format || "width" in p.format, id)
            val sort = p.extraArgs[p.extraArgs.indexOf("-S") + 1].split(',')
            assertEquals("res:$limit", sort.first(), id) // yt-dlp's `res` is min(width, height)
            assertEquals(listOf("res:$limit", "vcodec:h264", "acodec:aac", "ext:mp4:m4a"), sort, id)
        }
        assertEquals(DownloadPresets.mp4Args(720), DownloadPresets.byId("best-720").extraArgs)
        assertEquals(DownloadPresets.mp4Args(1080), DownloadPresets.byId("best-1080").extraArgs)
    }

    @Test fun anMp4PresetMergesIntoAnMp4EvenWhenTheSiteHasNoH264() {
        // A no-H.264 fallback that is a MERGE (VP9 video + Opus audio, which would otherwise be .mkv) comes out as .mp4. A
        // single-file fallback in another container (a lone .webm on a rare host) keeps its own: --merge-output-format does not
        // touch it, and --remux-video mp4 would make the whole download fail when the codecs cannot go into an mp4 (Vorbis),
        // so it is deliberately absent. Nothing is ever re-encoded.
        for (id in listOf("best-1080", "best-720")) {
            val args = DownloadPresets.byId(id).extraArgs
            assertEquals("mp4", args[args.indexOf("--merge-output-format") + 1], id)
            assertFalse("--remux-video" in args, id)
            assertFalse(args.any { it == "-c:v" || it.startsWith("--recode") || it == "--postprocessor-args" }, id)
        }
    }

    @Test fun theMp4DescriptionsPromiseOnlyWhatThePresetsDo() {
        // 1080 is an upper bound on the SHORT side (a 720p-only source stays 720p; on a modern Facebook video the only H.264 is its
        // small sd, while an older one's hd is H.264 720p).
        assertEquals("H.264 up to 1080p, widely compatible", DownloadPresets.byId("best-1080").desc)
        assertEquals("Smaller file, good quality", DownloadPresets.byId("best-720").desc)
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
