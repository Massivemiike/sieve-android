package com.sieve.queue.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Download failures are decided by the same yt-dlp rule table the user-facing message comes from
 * (desktop `human.transient`: rate limit, 403, network). The table has no 5xx or fragment rule, so
 * those are PERMANENT here exactly as on desktop.
 */
@RunWith(Parameterized::class)
class RetryClassifierTest(private val msg: String, private val expected: RetryClass) {
    @Test fun classifies() {
        assertEquals(expected, RetryClassifier.classify(FailureInfo(message = msg), JobKind.DOWNLOAD))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} -> {1}")
        fun data(): List<Array<Any>> = listOf(
            arrayOf("HTTP Error 429: Too Many Requests", RetryClass.TRANSIENT),
            arrayOf("Unable to download webpage: throttled", RetryClass.TRANSIENT),
            arrayOf("[Errno 104] Network is unreachable", RetryClass.TRANSIENT),
            arrayOf("The read operation timed out", RetryClass.TRANSIENT),
            arrayOf("Connection reset by peer", RetryClass.TRANSIENT),
            arrayOf("Temporary failure in name resolution", RetryClass.TRANSIENT),
            arrayOf("ERROR: unable to download video data: HTTP Error 403: Forbidden", RetryClass.TRANSIENT),
            arrayOf(
                "ERROR: [youtube] abc: Unable to download webpage: <urlopen error EOF occurred in violation of protocol (_ssl.c:1006)>",
                RetryClass.TRANSIENT,
            ),
            arrayOf("ERROR: [vimeo] 1: Unable to download JSON metadata: <urlopen error [Errno 111] Connection refused>", RetryClass.TRANSIENT),
            arrayOf("HTTP Error 404: Not Found", RetryClass.PERMANENT),
            arrayOf("This video is private", RetryClass.PERMANENT),
            arrayOf("Requested format is not available", RetryClass.PERMANENT),
            arrayOf("This video is not available in your country", RetryClass.PERMANENT),
            arrayOf("Sign in to confirm your age", RetryClass.PERMANENT),
            arrayOf("Unsupported URL", RetryClass.PERMANENT),
            // Not in the desktop table (yt-dlp retries these itself before it gives up), so no extra retry here.
            arrayOf("HTTP Error 503: Service Unavailable", RetryClass.PERMANENT),
            arrayOf("Unable to download fragment 12", RetryClass.PERMANENT),
            arrayOf("yt-dlp exited 1", RetryClass.PERMANENT),
        )
    }
}

class RetryClassifierExitTest {
    private fun download(info: FailureInfo) = RetryClassifier.classify(info, JobKind.DOWNLOAD)

    @Test fun `real yt-dlp 429 error line is transient`() {
        val msg = "ERROR: [youtube] abc: Unable to download API page: HTTP Error 429: Too Many Requests " +
            "(caused by HTTPError(429)); please report this issue on https://github.com/yt-dlp/yt-dlp/issues?q= , " +
            "filling out the appropriate issue template."
        assertEquals(RetryClass.TRANSIENT, download(FailureInfo(msg, exitCode = 1)))
    }

    @Test fun `real yt-dlp network failure is transient`() {
        val msg = "ERROR: [youtube] abc: Unable to download webpage: <urlopen error [Errno -3] Temporary failure in name resolution>"
        assertEquals(RetryClass.TRANSIENT, download(FailureInfo(msg, exitCode = 1)))
    }

    @Test fun `a 403 is transient like on desktop`() {
        val msg = "ERROR: unable to download video data: HTTP Error 403: Forbidden"
        assertEquals(RetryClass.TRANSIENT, download(FailureInfo(msg, exitCode = 1, stderrTail = msg)))
    }

    @Test fun `generic exit message stays permanent`() {
        assertEquals(RetryClass.PERMANENT, download(FailureInfo("yt-dlp exited 1", exitCode = 1)))
    }

    @Test fun `urls do not decide the verdict`() {
        // "login" in a URL slug would otherwise make a network failure PERMANENT...
        val net = "ERROR: Unable to download webpage: The read operation timed out (https://example.com/login/page)"
        assertEquals(RetryClass.TRANSIENT, download(FailureInfo(net, exitCode = 1)))
        // ...and "429" in a path must not make an unknown failure retry.
        val odd = "ERROR: Something odd happened at https://example.com/watch/429/throttled"
        assertEquals(RetryClass.PERMANENT, download(FailureInfo(odd, exitCode = 1)))
    }

    // Desktop passes --no-warnings and classifies only the final attempt's ERROR lines; a WARNING line must never
    // decide the verdict, in either direction, even when it rides along in the stderr tail.
    @Test fun `a permanent-looking warning does not block the retry of a transient error`() {
        val blob = "WARNING: [youtube] abc: tv client https formats require a GVS PO Token which was not provided. " +
            "They will be skipped as they may yield HTTP Error 403\n" +
            "WARNING: [youtube] abc: Sign in to confirm your age. This video is not available\n" +
            "ERROR: [youtube] abc: Unable to download webpage: The read operation timed out\n"
        val info = FailureInfo(
            message = "ERROR: [youtube] abc: Unable to download webpage: The read operation timed out",
            exitCode = 1, stderrTail = blob,
        )
        assertEquals(RetryClass.TRANSIENT, download(info))
    }

    @Test fun `a transient-looking warning does not make a permanent error retry`() {
        val blob = "WARNING: Unable to download video subtitles for 'en': HTTP Error 429: Too Many Requests\n" +
            "ERROR: Postprocessing: ffmpeg exited with code 1\n"
        val info = FailureInfo(message = "ERROR: Postprocessing: ffmpeg exited with code 1", exitCode = 1, stderrTail = blob)
        assertEquals(RetryClass.PERMANENT, download(info))
    }

    @Test fun `the ERROR lines in the tail count even when the message was cut short`() {
        // The message keeps only the first 500 chars of the ERROR lines; the verdict still sees the rest.
        val long = "ERROR: [generic] " + "x".repeat(600)
        val blob = long + "\nERROR: Unable to download webpage: The read operation timed out\n"
        assertEquals(RetryClass.TRANSIENT, download(FailureInfo(long.take(500), exitCode = 1, stderrTail = blob)))
    }

    // ffmpeg's stderr has no yt-dlp phrasing or `ERROR:` prefix, so transcodes keep the substring verdict.
    @Test fun `ffmpeg stderr tail signals permanent codec error`() {
        val c = RetryClassifier.classify(FailureInfo("exit 1", exitCode = 1, stderrTail = "Unknown encoder 'h264_nvenc'"), JobKind.TRANSCODE)
        assertEquals(RetryClass.PERMANENT, c)
    }

    @Test fun `stderr tail with connection reset is transient`() {
        val c = RetryClassifier.classify(FailureInfo("exit 1", exitCode = 1, stderrTail = "Connection reset by peer"), JobKind.TRANSCODE)
        assertEquals(RetryClass.TRANSIENT, c)
    }
}

/** The stall watchdog's verdict (`FfmpegRunner.STALL_SUMMARY`) is final, whatever else the tail of a hung run holds. */
class RetryClassifierStallTest {
    private val stalled = "ffmpeg stopped making progress"

    @Test fun `a stalled transcode is permanent`() {
        assertEquals(RetryClass.PERMANENT, RetryClassifier.classify(FailureInfo(stalled, exitCode = 124, stderrTail = null), JobKind.TRANSCODE))
    }

    @Test fun `a stray transient-looking word in the tail of a hung run does not make it retry`() {
        val tail = "[av1_mediacodec @ 0x7b] connection to the codec service timed out\nCodec2 component died: network"
        assertEquals(RetryClass.PERMANENT, RetryClassifier.classify(FailureInfo(stalled, exitCode = 124, stderrTail = tail), JobKind.TRANSCODE))
    }
}
