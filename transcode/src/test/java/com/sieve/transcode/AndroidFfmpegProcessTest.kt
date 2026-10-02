package com.sieve.transcode

import com.sieve.transcode.runner.FfmpegProcess
import com.sieve.transcode.runner.FfmpegProcessFactory
import com.sieve.transcode.runner.FfmpegRunner
import com.sieve.transcode.runner.android.AndroidFfmpegProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    // --- cancel on a hung ffmpeg: the S26 "first tap does nothing" defect --------------------------------------------

    /**
     * `java.lang.Process` as Android has it (libcore's UNIXProcess): destroy() is kill(pid, SIGTERM) and there is NO
     * destroyForcibly() of its own, so the inherited default `destroy(); return this` runs: SIGTERM again. The JVM's own
     * Process would SIGKILL here and hide the defect, so the real child sits behind this.
     */
    private class AndroidLikeProcess(private val real: Process) : Process() {
        val pid: Int = Regex("pid=(\\d+)").find(real.toString())!!.groupValues[1].toInt()
        override fun getOutputStream(): OutputStream = real.outputStream
        override fun getInputStream(): InputStream = real.inputStream
        override fun getErrorStream(): InputStream = real.errorStream
        override fun waitFor(): Int = real.waitFor()
        override fun exitValue(): Int = real.exitValue()
        override fun destroy() { signal("TERM") }
        override fun toString(): String = "Process[pid=$pid, hasExited=${!real.isAlive}]"
        fun signal(name: String) { ProcessBuilder("kill", "-$name", pid.toString()).start().waitFor() }
    }

    /** ffmpeg with its codec thread stuck: alive, deaf to `q` (nobody reads stdin) and to SIGTERM. */
    private fun spawnWedgedChild(): Process =
        ProcessBuilder("/bin/sh", "-c", "trap '' TERM; while true; do sleep 1; done").start()

    private val sh = File("/bin/sh")

    private fun sigkillWithKill9(pid: Int) { ProcessBuilder("kill", "-9", pid.toString()).start().waitFor() }

    private fun androidProcess(child: Process, sigkill: (Int) -> Unit = ::sigkillWithKill9, unreapedMs: Long = 3_000) =
        AndroidFfmpegProcess(AndroidLikeProcess(child), sigkill, unreapedMs, System::currentTimeMillis)

    @Test fun androidsProcessHasNoSigkill_destroyForciblyIsSigtermAgain() {
        assumeTrue("needs /bin/sh", sh.exists())
        val child = spawnWedgedChild()
        try {
            val android = AndroidLikeProcess(child)
            android.destroy()
            android.destroyForcibly()
            assertFalse("a child that ignores SIGTERM survives destroy() and destroyForcibly() on Android", child.waitFor(700, TimeUnit.MILLISECONDS))
        } finally {
            child.destroyForcibly()
        }
    }

    // The defect, end to end: ONE cancel (q -> SIGTERM -> SIGKILL) must end a wedged ffmpeg. With the Process API alone it
    // sent SIGTERM twice, and only the second tap's 3rd and 4th SIGTERM made ffmpeg's own handler hard-exit.
    @Test fun oneCancelKillsAChildThatIgnoresSigterm() = runBlocking {
        assumeTrue("needs /bin/sh", sh.exists())
        val child = spawnWedgedChild()
        try {
            val p = androidProcess(child)
            val t0 = System.nanoTime()
            val seenExit = FfmpegRunner(object : FfmpegProcessFactory {
                override fun start(binaryPath: String, args: List<String>): FfmpegProcess = p
            }, "/x").cancel(p, graceMs = 300, termGraceMs = 300)
            assertTrue("cancel saw the process go", seenExit)
            assertFalse(child.isAlive)
            assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 5_000_000_000L)
            assertEquals("killed by SIGKILL: 128 + 9", 137, child.exitValue())
        } finally {
            child.destroyForcibly()
        }
    }

    private class FakePid(private val text: String) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {}
        override fun toString(): String = text
    }

    @Test fun pidIsReadFromProcessToString() {
        assertEquals(4303, AndroidFfmpegProcess.pidOf(FakePid("Process[pid=4303, hasExited=false]")))
        assertEquals(77, AndroidFfmpegProcess.pidOf(FakePid("Process[pid=77, exitValue=\"not exited\"]")))
        assertNull(AndroidFfmpegProcess.pidOf(FakePid("java.lang.Object@1f")))
    }

    /** A process that never exits by itself, so only the code under test can end the wait. */
    private class ImmortalProcess(
        private val text: String = "Process[pid=77, hasExited=false]",
        private val stdin: OutputStream = ByteArrayOutputStream(),
        private val onDestroy: () -> Unit = {},
    ) : Process() {
        @Volatile var destroyForciblyCalls = 0
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { Thread.sleep(Long.MAX_VALUE); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { Thread.sleep(minOf(unit.toMillis(timeout), 50)); return false }
        override fun exitValue(): Int = throw IllegalThreadStateException("process hasn't exited")
        override fun destroy() = onDestroy()
        override fun destroyForcibly(): Process { destroyForciblyCalls++; return this }
        override fun toString(): String = text
    }

    @Test fun destroyForciblyDeliversSigkillToThePid() {
        val killed = mutableListOf<Int>()
        val fake = ImmortalProcess()
        val p = AndroidFfmpegProcess(fake, { killed += it }, 3_000, System::currentTimeMillis)
        p.destroyForcibly()
        assertEquals(listOf(77), killed)
        assertEquals("the Process API's SIGTERM-only stand-in is not used when the pid is known", 0, fake.destroyForciblyCalls)
    }

    @Test fun withoutAPidTheProcessApiIsTheBestThatIsLeft() {
        val fake = ImmortalProcess(text = "something else")
        val killed = mutableListOf<Int>()
        AndroidFfmpegProcess(fake, { killed += it }, 3_000, System::currentTimeMillis).destroyForcibly()
        assertTrue(killed.isEmpty())
        assertEquals(1, fake.destroyForciblyCalls)
    }

    @Test fun aFailedSigkillFallsBackTheSameWay() {
        val fake = ImmortalProcess()
        AndroidFfmpegProcess(fake, { throw SecurityException("EPERM") }, 3_000, System::currentTimeMillis).destroyForcibly()
        assertEquals(1, fake.destroyForciblyCalls)
    }

    // A child that survives SIGKILL (stuck in an uninterruptible driver call) must not hold the run, the queue slot and the
    // foreground service for as long as it lives.
    @Test fun aChildThatSurvivesSigkillIsDeclaredGoneAfterTheBound() = runBlocking {
        val p = AndroidFfmpegProcess(ImmortalProcess(), {}, 300, System::currentTimeMillis)
        assertFalse(p.awaitExit(100))
        p.destroyForcibly()
        assertEquals(AndroidFfmpegProcess.EXIT_UNREAPED, p.awaitExit())
        assertTrue(p.awaitExit(100))
    }

    /** A stdin whose write never returns, e.g. a pipe lock held by a stuck writer. */
    private class BlockedStdin(val release: CountDownLatch = CountDownLatch(1)) : OutputStream() {
        override fun write(b: Int) { release.await(30, TimeUnit.SECONDS) }
        override fun write(b: ByteArray) { release.await(30, TimeUnit.SECONDS) }
        override fun write(b: ByteArray, off: Int, len: Int) { release.await(30, TimeUnit.SECONDS) }
    }

    // "An awaited q" was one guess for the first-tap miss. It is not what happened, but it must not be able to.
    @Test fun aBlockedQNeverHoldsUpTheEscalation() = runBlocking {
        val stdin = BlockedStdin()
        val dead = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = object : Process() {
            override fun getOutputStream(): OutputStream = stdin
            override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int = 0
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { Thread.sleep(minOf(unit.toMillis(timeout), 50)); return dead.get() }
            override fun exitValue(): Int = if (dead.get()) 137 else throw IllegalThreadStateException()
            override fun destroy() {}
            override fun toString(): String = "Process[pid=77, hasExited=${dead.get()}]"
        }
        val p = AndroidFfmpegProcess(fake, { dead.set(true) }, 3_000, System::currentTimeMillis)
        val t0 = System.nanoTime()
        try {
            val seen = FfmpegRunner(object : FfmpegProcessFactory {
                override fun start(binaryPath: String, args: List<String>): FfmpegProcess = p
            }, "/x").cancel(p, graceMs = 200, termGraceMs = 200)
            assertTrue(seen)
            assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 6_000_000_000L)
        } finally {
            stdin.release.countDown()
        }
    }

    @Test fun aBlockedWriteToStdinIsAbandonedAsAnIoException() = runBlocking {
        val stdin = BlockedStdin()
        val p = AndroidFfmpegProcess(ImmortalProcess(stdin = stdin), {}, 3_000, System::currentTimeMillis)
        try {
            val t0 = System.nanoTime()
            assertThrows(IOException::class.java) { runBlocking { p.writeStdin("q") } }
            assertTrue(System.nanoTime() - t0 < 5_000_000_000L)
        } finally {
            stdin.release.countDown()
        }
    }

    @Test fun aBlockedDestroyIsAbandonedAfterItsBound() {
        val release = CountDownLatch(1)
        val p = AndroidFfmpegProcess(ImmortalProcess(onDestroy = { release.await(30, TimeUnit.SECONDS) }), {}, 3_000, System::currentTimeMillis)
        try {
            val t0 = System.nanoTime()
            p.destroy()
            assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 5_000_000_000L)
        } finally {
            release.countDown()
        }
    }

    // A pipe line that never ends cannot grow without bound: it is read to its end and cut at MAX_LINE_CHARS.
    @Test fun anEndlessLineIsCutNotBuffered() = runTest {
        val endless = ByteArray(2_000_000) { 'x'.code.toByte() } + "\nok\n".toByteArray()
        val fake = object : Process() {
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream(): InputStream = ByteArrayInputStream(endless)
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int = 0
            override fun exitValue(): Int = 0
            override fun destroy() {}
        }
        val lines = AndroidFfmpegProcess(fake).stdout.toList()
        assertEquals(2, lines.size)
        assertEquals(AndroidFfmpegProcess.MAX_LINE_CHARS + 1, lines[0].length) // the cut line plus the re-added newline
        assertEquals("ok\n", lines[1])
    }
}
