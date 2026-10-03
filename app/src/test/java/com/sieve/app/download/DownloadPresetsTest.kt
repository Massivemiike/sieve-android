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
            "b[format_id=sd][ext=mp4]", // Facebook: only its progressive sd is H.264 (hd is VP9)
            "bv*+ba", // no H.264 at all: any video + audio
            "b", // ...and anything, so a download never fails with "format not available"
        )
        for (id in listOf("best-1080", "best-720")) {
            assertEquals(expected, DownloadPresets.byId(id).format.split('/'), id)
            assertEquals(DownloadPresets.MP4_FORMAT, DownloadPresets.byId(id).format, id)
        }
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

    @Test fun anMp4PresetStaysAnMp4EvenWhenTheSiteHasNoH264() {
        // VP9 + Opus would otherwise merge into .mkv; nothing is ever re-encoded or remuxed to get there.
        for (id in listOf("best-1080", "best-720")) {
            val args = DownloadPresets.byId(id).extraArgs
            assertEquals("mp4", args[args.indexOf("--merge-output-format") + 1], id)
            assertFalse("--remux-video" in args, id)
            assertFalse(args.any { it == "-c:v" || it.startsWith("--recode") || it == "--postprocessor-args" }, id)
        }
    }

    @Test fun theMp4DescriptionsPromiseOnlyWhatThePresetsDo() {
        // 1080 is an upper bound on the SHORT side (a 720p-only source stays 720p; Facebook's only H.264 is its 360p sd).
        assertEquals("H.264 up to 1080p, widely compatible", DownloadPresets.byId("best-1080").desc)
        assertEquals("Smaller file, good quality", DownloadPresets.byId("best-720").desc)
    }

    // ---- MP3 320: a constant bitrate, not LAME V0 ----

    @Test fun theMp3PresetEncodesAConstant320() {
        val p = DownloadPresets.byId("audio-mp3")
        assertEquals("bestaudio/best", p.format)
        assertEquals(listOf("-x", "--audio-format", "mp3", "--audio-quality", "320K"), p.extraArgs)
        assertNotEquals("0", p.extraArgs.last()) // 0..9 select VBR (V0 is about 245 kbps); >= 10 means -b:a Nk
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
