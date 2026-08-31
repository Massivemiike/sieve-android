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
    @Test fun `ffmpeg stderr tail signals permanent codec error`() {
        val c = RetryClassifier.classify(FailureInfo("exit 1", exitCode = 1, stderrTail = "Unknown encoder 'h264_nvenc'"))
        assertEquals(RetryClass.PERMANENT, c)
    }

    @Test fun `stderr tail with connection reset is transient`() {
        val c = RetryClassifier.classify(FailureInfo("exit 1", exitCode = 1, stderrTail = "Connection reset by peer"))
        assertEquals(RetryClass.TRANSIENT, c)
    }
}
