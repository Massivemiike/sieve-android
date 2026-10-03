package com.sieve.queue.core

import com.sieve.engine.args.YtdlpArgs
import com.sieve.engine.parse.ErrorKind
import com.sieve.engine.parse.YtdlpErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private fun row(
        error: String?,
        title: String = "Cats",
        template: String = oldTemplate,
        spec: JobSpec = JobSpec.Download("https://www.facebook.com/watch/?v=1", emptyList()),
    ) = QueueJob("j", spec, OutputRequest("Downloads/Sieve", template), status = DownloadStatus.FAILED, title = title, error = error)

    @Test fun `the caption title that overflowed v1_0_3 is named as the cause and Retry is promised`() {
        assertEquals(tooLong, FailureText.text(row("yt-dlp exited 1", title = captionTitle)))
        assertEquals(ErrorKind.DISK, FailureText.humanize(row("yt-dlp exited 1", title = captionTitle)).kind)
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
        assertEquals(
            "yt-dlp stopped without reporting a reason (exit 1) — Retry may work.",
            FailureText.text(row("yt-dlp exited 1", title = captionTitle, template = YtdlpArgs.DEFAULT_TEMPLATE)),
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
            assertEquals(raw, YtdlpErrors.format(YtdlpErrors.humanize(raw)), FailureText.text(row(raw, title = captionTitle)))
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
