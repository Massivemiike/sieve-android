package com.sieve.queue.core

import com.sieve.engine.args.YtdlpArgs
import com.sieve.engine.parse.ErrorKind
import com.sieve.engine.parse.YtdlpErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a FAILED row says about why. Rows failed by v1.0.3 or older carry only "yt-dlp exited N" (those versions never kept
 * yt-dlp's error); [FailureText] words that from the row's own title and template when it is shown. Everything else is
 * [YtdlpErrors], as it always was.
 */
class FailureTextTest {
    private val oldTemplate = "%(title)s [%(id)s].%(ext)s"

    /** A Facebook post caption, emoji and all: 360 bytes, 80 characters. */
    private val captionTitle = "🎬🔥 ".repeat(40)

    private val tooLong = "The title made the file name too long — Fixed, so Retry will work."
    private val mayBeTooLong = "The title may have made the file name too long — Fixed, so Retry should work."
    private val noReason = "yt-dlp stopped without reporting a reason (exit 1) — Retry may work."

    /**
     * A Facebook caption of 221 bytes (77 characters of emoji and text), the size measured on
     * facebook.com/facebook/videos/1370361647863285 (id 16 digits, format ids "<16 digits>v" / "<16 digits>a"). Its final name is
     * 244 bytes and fits; the part file of one of its streams, which 1.0.3 had to create, is 268 and does not.
     */
    private val facebookCaption = "🎬🔥 ".repeat(24) + "Cats!"

    private fun row(
        error: String?,
        title: String = "Cats",
        template: String = oldTemplate,
        spec: JobSpec = JobSpec.Download("https://www.facebook.com/watch/?v=1", emptyList()),
        site: String = "Unknown",
    ) = QueueJob(
        "j", spec, OutputRequest("Downloads/Sieve", template),
        status = DownloadStatus.FAILED, title = title, site = site, error = error,
    )

    @Test fun `the caption title that overflowed v1_0_3 is named as the cause and Retry is promised`() {
        assertEquals(tooLong, FailureText.text(row("yt-dlp exited 1", title = captionTitle)))
        assertEquals(ErrorKind.DISK, FailureText.humanize(row("yt-dlp exited 1", title = captionTitle)).kind)
    }

    @Test fun `the Facebook caption that overflowed the part file name is named as the likely cause`() {
        // The measured case is what the text claims: the final name fits, the part file of one stream does not.
        val partFile = "$facebookCaption [1370361647863285].f1234567890123456v.mp4.part"
        assertEquals(221, facebookCaption.toByteArray().size)
        assertEquals(244, "$facebookCaption [1370361647863285].mp4".toByteArray().size)
        assertEquals(268, partFile.toByteArray().size)
        assertTrue(partFile.toByteArray().size > YtdlpArgs.MAX_FILENAME_BYTES)

        // Not proven (the row has neither the id nor the format id): the claim is hedged, and so is its hint.
        assertEquals(mayBeTooLong, FailureText.text(row("yt-dlp exited 1", title = facebookCaption, site = "facebook")))
        assertEquals(ErrorKind.DISK, FailureText.humanize(row("yt-dlp exited 1", title = facebookCaption, site = "facebook")).kind)
        for (site in listOf("facebook:reel", "facebook:ads", "Facebook")) {
            assertEquals(site, mayBeTooLong, FailureText.text(row("yt-dlp exited 1", title = facebookCaption, site = site)))
        }
    }

    @Test fun `on Facebook the hedged verdict starts where the part file starts to overflow`() {
        // 255 - 47 (" [<16 digits>]" and ".f<17 bytes>.mp4.part") = 208
        assertEquals(noReason, FailureText.text(row("yt-dlp exited 1", title = "a".repeat(208), site = "facebook")))
        assertEquals(mayBeTooLong, FailureText.text(row("yt-dlp exited 1", title = "a".repeat(209), site = "facebook")))
    }

    @Test fun `a title that fits is not blamed on Facebook, nor a long one on a site that is not known`() {
        val youtubeTitle = "x".repeat(160)
        assertEquals(noReason, FailureText.text(row("yt-dlp exited 1", title = youtubeTitle, site = "youtube")))
        assertEquals(noReason, FailureText.text(row("yt-dlp exited 1", title = youtubeTitle, site = "facebook")))
        // 221 bytes is only a likely overflow where the suffix is known to be long.
        for (site in listOf("youtube", "instagram", "twitter", "Unknown", "", "facebookish")) {
            assertEquals(site, noReason, FailureText.text(row("yt-dlp exited 1", title = facebookCaption, site = site)))
        }
    }

    @Test fun `a title past the limit on its own is named as the cause on any site`() {
        for (site in listOf("youtube", "facebook", "Unknown")) {
            assertEquals(site, tooLong, FailureText.text(row("yt-dlp exited 1", title = captionTitle, site = site)))
        }
    }

    @Test fun `the title is blamed only for exit code 1`() {
        // 1.0.3 saw exit 1 for the failed download; another code (a killed process) is not that failure, and 1.0.4 clamps the title.
        for (code in listOf(137, 2, 255, -1)) {
            val expected = "yt-dlp stopped without reporting a reason (exit $code) — Retry may work."
            assertEquals("$code caption", expected, FailureText.text(row("yt-dlp exited $code", title = captionTitle)))
            assertEquals("$code facebook", expected, FailureText.text(row("yt-dlp exited $code", title = facebookCaption, site = "facebook")))
        }
    }

    @Test fun `a short title says yt-dlp stopped without a reason, with its exit code`() {
        assertEquals(
            "yt-dlp stopped without reporting a reason (exit 1) — Retry may work.",
            FailureText.text(row("yt-dlp exited 1")),
        )
        assertEquals(
            "yt-dlp stopped without reporting a reason (exit 137) — Retry may work.",
            FailureText.text(row("yt-dlp exited 137")),
        )
    }

    @Test fun `a title that fits is not blamed even when its row has the old template`() {
        assertNotEquals(tooLong, FailureText.text(row("yt-dlp exited 1", title = "a".repeat(200))))
    }

    @Test fun `a row whose title was already clamped is never blamed on the title`() {
        // v1.0.4's own "yt-dlp exited 1" (yt-dlp printed nothing): the file name was fine, so say what is true.
        assertEquals(noReason, FailureText.text(row("yt-dlp exited 1", title = captionTitle, template = YtdlpArgs.DEFAULT_TEMPLATE)))
        assertEquals(
            noReason,
            FailureText.text(row("yt-dlp exited 1", title = facebookCaption, template = YtdlpArgs.DEFAULT_TEMPLATE, site = "facebook")),
        )
    }

    @Test fun `a real v1_0_4 failure message is worded by the rule table, as before`() {
        for (raw in listOf(
            "ERROR: HTTP Error 429: Too Many Requests",
            "ERROR: [facebook] 1: Unable to download webpage: HTTP Error 403: Forbidden",
            "ERROR: [Errno 36] File name too long: '/data/user/0/com.sieve.app/files/work/j/x.f1.mp4.part'",
            "WARNING: x\nERROR: Video unavailable",
        )) {
            // Even for a row whose title would overflow the old template: yt-dlp's own words win.
            val expected = YtdlpErrors.format(YtdlpErrors.humanize(raw))
            assertEquals(raw, expected, FailureText.text(row(raw, title = captionTitle)))
            assertEquals(raw, expected, FailureText.text(row(raw, title = facebookCaption, site = "facebook")))
            assertEquals(raw, expected, FailureText.text(row(raw)))
        }
        assertEquals(
            "Rate-limited by the site — Wait a few minutes and retry.",
            FailureText.text(row("ERROR: HTTP Error 429: Too Many Requests", title = captionTitle)),
        )
    }

    @Test fun `text that merely mentions exited is not the opaque message and is left alone`() {
        for (raw in listOf(
            "yt-dlp exited with code 2",
            "yt-dlp exited 1 early",
            "yt-dlp exited 1\n",
            " yt-dlp exited 1",
            "yt-dlp exited",
            "yt-dlp exited x",
            "ERROR: yt-dlp exited 1",
            "ffmpeg exited 1",
            "the yt-dlp exited 1",
        )) {
            val expected = YtdlpErrors.format(YtdlpErrors.humanize(raw))
            assertEquals(raw, expected, FailureText.text(row(raw, title = captionTitle)))
            assertEquals(raw, expected, FailureText.text(row(raw, title = facebookCaption, site = "facebook")))
        }
    }

    @Test fun `a failure without text is as before`() {
        assertEquals("Download failed", FailureText.text(row(null)))
        assertEquals("Download failed", FailureText.text(row("")))
    }

    @Test fun `a transcode row is never reworded`() {
        val tx = JobSpec.Transcode("/in.mkv", emptyList(), 10.0, false)
        assertEquals("yt-dlp exited 1", FailureText.text(row("yt-dlp exited 1", title = captionTitle, spec = tx)))
        assertEquals("ffmpeg exited 1", FailureText.text(row("ffmpeg exited 1", spec = tx)))
    }

    @Test fun `the stored error is left raw for the retry rules`() {
        val job = row("yt-dlp exited 1", title = captionTitle)
        FailureText.text(job)
        assertEquals("yt-dlp exited 1", job.error)
        assertEquals(
            RetryClass.PERMANENT,
            RetryClassifier.classify(FailureInfo(job.error!!, exitCode = 1), JobKind.DOWNLOAD),
        )
    }
}
