package com.sieve.transcode

import com.sieve.transcode.runner.android.AndroidFfmpegProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import kotlin.time.Duration.Companion.seconds

/**
 * The real process seam, against a real child process (`sleep`, skipped where it does not exist).
 * These are the waits that cancel's SIGTERM grace and a cancelled collector depend on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidFfmpegProcessTest {
    private val sleep = File("/bin/sleep")

    private fun spawn(): AndroidFfmpegProcess = AndroidFfmpegProcess(ProcessBuilder(sleep.path, "30").start())

    @Test fun timedWaitHonoursItsBoundWhileRunningThenSeesTheExit() {
        assumeTrue("needs /bin/sleep", sleep.exists())
        runTest(timeout = 20.seconds) {
            val p = spawn()
            try {
                assertFalse(p.awaitExit(100)) // still running: returns false after ~100 ms, does not block for 30 s
                p.destroy()
                assertTrue(p.awaitExit(5_000))
            } finally {
                p.destroyForcibly()
            }
        }
    }

    // A plain waitFor() parks its IO thread until the child dies; it must be interruptible so a cancelled
    // collector (service teardown) releases the thread.
    @Test fun untimedWaitIsReleasedByCancellation() {
        assumeTrue("needs /bin/sleep", sleep.exists())
        runTest(timeout = 20.seconds) {
            val p = spawn()
            try {
                val waiter = launch(Dispatchers.Default) { p.awaitExit() }
                withContext(Dispatchers.Default) { delay(200) } // let it reach waitFor()
                waiter.cancelAndJoin()
            } finally {
                p.destroyForcibly()
            }
        }
    }

    /** Serves [text], then fails the next read the way Android does when destroy() closes the pipe mid-read. */
    private class ClosedUnderReader(text: String) : InputStream() {
        private val bytes = text.toByteArray()
        private var i = 0
        override fun read(): Int {
            if (i < bytes.size) return bytes[i++].toInt() and 0xFF
            throw InterruptedIOException("read interrupted by close() on another thread")
        }
    }

    private class FakeProcess(private val out: InputStream, private val err: InputStream) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = out
        override fun getErrorStream(): InputStream = err
        override fun waitFor(): Int = 255
        override fun exitValue(): Int = 255
        override fun destroy() {}
    }

    // Cancel escalates to destroy(), which closes ffmpeg's pipes while both readers are blocked in read(): that
    // used to escape as an uncaught InterruptedIOException and crash the app (seen on the S26).
    @Test fun aPipeClosedUnderTheReaderEndsTheOutputInsteadOfThrowing() = runTest {
        val p = AndroidFfmpegProcess(FakeProcess(ClosedUnderReader("out_time_us=1\nprogress=continue\n"), ClosedUnderReader("frame=1\n")))
        assertEquals(listOf("out_time_us=1\n", "progress=continue\n"), p.stdout.toList())
        assertEquals(listOf("frame=1"), p.stderr.toList())
    }
}
