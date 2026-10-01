package com.sieve.queue.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RetryClassifierTest(private val msg: String, private val expected: RetryClass) {
    @Test fun classifies() {
        assertEquals(expected, RetryClassifier.classify(FailureInfo(message = msg)))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} -> {1}")
        fun data() = listOf(
            arrayOf("HTTP Error 429: Too Many Requests", RetryClass.TRANSIENT),
            arrayOf("Unable to download webpage: throttled", RetryClass.TRANSIENT),
            arrayOf("[Errno 104] Network is unreachable", RetryClass.TRANSIENT),
            arrayOf("The read operation timed out", RetryClass.TRANSIENT),
            arrayOf("Connection reset by peer", RetryClass.TRANSIENT),
            arrayOf("Temporary failure in name resolution", RetryClass.TRANSIENT),
            arrayOf("HTTP Error 503: Service Unavailable", RetryClass.TRANSIENT),
            arrayOf("Unable to download fragment 12", RetryClass.TRANSIENT),
            arrayOf("HTTP Error 403: Forbidden", RetryClass.PERMANENT),
            arrayOf("HTTP Error 404: Not Found", RetryClass.PERMANENT),
            arrayOf("This video is private", RetryClass.PERMANENT),
            arrayOf("Requested format is not available", RetryClass.PERMANENT),
            arrayOf("This video is not available in your country", RetryClass.PERMANENT),
            arrayOf("Sign in to confirm your age", RetryClass.PERMANENT),
            arrayOf("Unsupported URL", RetryClass.PERMANENT),
            arrayOf("Unknown encoder libx265", RetryClass.PERMANENT),
        )
    }
}

class RetryClassifierExitTest {
    @Test fun `real yt-dlp 429 error line is transient`() {
        val msg = "ERROR: [youtube] abc: Unable to download API page: HTTP Error 429: Too Many Requests " +
            "(caused by HTTPError(429)); please report this issue on https://github.com/yt-dlp/yt-dlp/issues?q= , " +
            "filling out the appropriate issue template."
        assertEquals(RetryClass.TRANSIENT, RetryClassifier.classify(FailureInfo(msg, exitCode = 1)))
    }

    @Test fun `real yt-dlp network failure is transient`() {
        val msg = "ERROR: [youtube] abc: Unable to download webpage: <urlopen error [Errno -3] Temporary failure in name resolution>"
        assertEquals(RetryClass.TRANSIENT, RetryClassifier.classify(FailureInfo(msg, exitCode = 1)))
    }

    @Test fun `generic exit message stays permanent`() {
        assertEquals(RetryClass.PERMANENT, RetryClassifier.classify(FailureInfo("yt-dlp exited 1", exitCode = 1)))
    }

    @Test fun `urls do not decide the verdict`() {
        // "login" in a URL slug would otherwise make a network failure PERMANENT...
        val net = "ERROR: Unable to download webpage: The read operation timed out (https://example.com/login/page)"
        assertEquals(RetryClass.TRANSIENT, RetryClassifier.classify(FailureInfo(net, exitCode = 1)))
        // ...and "429" in a path must not make an unknown failure retry.
        val odd = "ERROR: Something odd happened at https://example.com/watch/429/throttled"
        assertEquals(RetryClass.PERMANENT, RetryClassifier.classify(FailureInfo(odd, exitCode = 1)))
    }

    @Test fun `ffmpeg stderr tail signals permanent codec error`() {
        val c = RetryClassifier.classify(FailureInfo("exit 1", exitCode = 1, stderrTail = "Unknown encoder 'h264_nvenc'"))
        assertEquals(RetryClass.PERMANENT, c)
    }

    @Test fun `stderr tail with connection reset is transient`() {
        val c = RetryClassifier.classify(FailureInfo("exit 1", exitCode = 1, stderrTail = "Connection reset by peer"))
        assertEquals(RetryClass.TRANSIENT, c)
    }
}
