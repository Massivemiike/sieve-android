package com.sieve.transcode

import com.sieve.transcode.runner.FfmpegProcess
import com.sieve.transcode.runner.FfmpegProcessFactory
import com.sieve.transcode.runner.FfmpegRunner
import com.sieve.transcode.runner.TranscodeEvent
import com.sieve.transcode.runner.TranscodeJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private class FakeFfmpegProcess(
    stdout: List<String> = emptyList(),
    stderr: List<String> = emptyList(),
    private val exit: Int = 0,
    private val exitDelayMs: Long = 0,
    /** Thrown by [writeStdin] — what writing 'q' to an already-exited ffmpeg does (EPIPE / stream closed). */
    private val stdinFailure: IOException? = null,
) : FfmpegProcess {
    override val stdout: Flow<String> = stdout.asFlow()
    override val stderr: Flow<String> = stderr.asFlow()
    val stdinWrites = mutableListOf<String>()
    var destroyCount = 0
    var destroyForciblyCount = 0
    override suspend fun writeStdin(text: String) {
        stdinFailure?.let { throw it }
        stdinWrites.add(text)
    }
    override fun destroy() { destroyCount++ }
    override fun destroyForcibly() { destroyForciblyCount++ }
    override suspend fun awaitExit(): Int {
        if (exitDelayMs > 0) delay(exitDelayMs)
        return exit
    }
}

/**
 * A wedged ffmpeg (stuck in a native MediaCodec call): ignores 'q' and SIGTERM, dies only to SIGKILL.
 * [awaitExit] is a plain blocking wait that coroutine cancellation cannot interrupt (like `waitFor()`
 * inside `withContext(IO)`); only the timed variant honours its bound.
 */
private class WedgedFfmpegProcess : FfmpegProcess {
    private val killed = CountDownLatch(1)
    override val stdout: Flow<String> = emptyFlow()
    override val stderr: Flow<String> = emptyFlow()
    val stdinWrites = mutableListOf<String>()
    var destroyCount = 0
    var destroyForciblyCount = 0
    override suspend fun writeStdin(text: String) { stdinWrites.add(text) }
    override fun destroy() { destroyCount++ } // SIGTERM is ignored
    override fun destroyForcibly() { destroyForciblyCount++; killed.countDown() }
    // Bounded only so a regression fails the test instead of wedging the whole test JVM.
    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { killed.await(15, TimeUnit.SECONDS); 137 }
    override suspend fun awaitExit(timeoutMs: Long): Boolean =
        withContext(Dispatchers.IO) { killed.await(timeoutMs, TimeUnit.MILLISECONDS) }
}

private class FakeFfmpegProcessFactory(private val queue: List<FakeFfmpegProcess>) : FfmpegProcessFactory {
    val calls = mutableListOf<List<String>>()
    private var idx = 0
    override fun start(binaryPath: String, args: List<String>): FfmpegProcess {
        calls.add(args)
        return queue[idx++]
    }
}

/** Task 17: FfmpegRunner over the process seam. */
@OptIn(ExperimentalCoroutinesApi::class)
class FfmpegRunnerTest {

    @Test fun A1_fullArgsOrder() {
        assertEquals(
            listOf("-y", "-progress", "pipe:1", "-i", "a.mp4", "-c:v", "libx265", "-crf", "23", "b.mp4"),
            FfmpegRunner.buildFullArgs(TranscodeJob("a.mp4", "b.mp4", listOf("-c:v", "libx265", "-crf", "23"), 10.0, false)),
        )
    }

    @Test fun A2_presetArgsSitBetweenInputAndOutput() {
        val args = FfmpegRunner.buildFullArgs(TranscodeJob("in", "out", listOf("-vn", "-c:a", "flac"), null, false))
        assertEquals("in", args[args.indexOf("-i") + 1])
        assertEquals("out", args.last())
        assertTrue(args.indexOf("-vn") in (args.indexOf("in") + 1) until (args.size - 1))
    }

    @Test fun F1_twoProgressThenDone() = runTest {
        val fac = FakeFfmpegProcessFactory(
            listOf(FakeFfmpegProcess(stdout = listOf("out_time_us=1000000\nprogress=continue\n", "out_time_us=2000000\nprogress=continue\n"), exit = 0)),
        )
        val ev = FfmpegRunner(fac, "/lib/libsieveffmpeg.so").run(TranscodeJob("a", "b", listOf("-c:v", "libx264"), 10.0, false)).toList()
        assertEquals(2, ev.count { it is TranscodeEvent.Progress })
        assertTrue(ev.last() is TranscodeEvent.Done && (ev.last() as TranscodeEvent.Done).exitCode == 0)
        assertNull((ev.last() as TranscodeEvent.Done).errorSummary)
    }

    @Test fun F2_zeroOutTimeBlocksAreFiltered() = runTest {
        val fac = FakeFfmpegProcessFactory(
            listOf(FakeFfmpegProcess(stdout = listOf("out_time_us=0\nprogress=continue\n", "out_time_us=3000000\nprogress=continue\n"), exit = 0)),
        )
        val ev = FfmpegRunner(fac, "/x").run(TranscodeJob("a", "b", listOf("-c:v", "libx264"), 10.0, false)).toList()
        assertEquals(1, ev.count { it is TranscodeEvent.Progress }) // the 0-block is dropped
    }

    @Test fun F3_hwInitFailureRetriesSoftware() = runTest {
        val fac = FakeFfmpegProcessFactory(
            listOf(
                FakeFfmpegProcess(stderr = listOf("[h264_mediacodec] Cannot open encoder"), exit = 1),
                FakeFfmpegProcess(stdout = listOf("out_time_us=1000000\nprogress=end\n"), exit = 0),
            ),
        )
        val ev = FfmpegRunner(fac, "/lib/x").run(TranscodeJob("a", "b", listOf("-c:v", "h264_mediacodec"), 10.0, usedHardwareEncoder = true)).toList()
        assertTrue(ev.any { it is TranscodeEvent.Log && it.line.contains("retrying on software") })
        assertEquals(2, fac.calls.size)
        assertTrue(fac.calls[1].contains("libx264")) // demoted -c:v
        assertTrue(fac.calls[1].windowed(2).contains(listOf("-pix_fmt", "yuv420p"))) // and the SW 8-bit 4:2:0 default
        assertEquals(0, (ev.last() as TranscodeEvent.Done).exitCode)
    }

    @Test fun F3b_demotedH264GetsPixFmtButHevcOnlySwapsTheCodec() {
        // The HW args carry no -pix_fmt (MediaCodec takes nv12); once demoted to libx264 a 10-bit/4:2:2
        // source would otherwise come out as High10/4:2:2 H.264 that most players reject.
        assertEquals(
            listOf("-c:v", "libx264", "-b:v", "6000k", "-pix_fmt", "yuv420p", "-vf", "scale=-2:1080", "-c:a", "aac"),
            FfmpegRunner.demoteToSoftware(listOf("-c:v", "h264_mediacodec", "-b:v", "6000k", "-vf", "scale=-2:1080", "-c:a", "aac")),
        )
        assertEquals(
            listOf("-c:v", "libx265", "-b:v", "3000k", "-tag:v", "hvc1"),
            FfmpegRunner.demoteToSoftware(listOf("-c:v", "hevc_mediacodec", "-b:v", "3000k", "-tag:v", "hvc1")),
        )
    }

    @Test fun F4_nonHwFailureNoRetry() = runTest {
        val fac = FakeFfmpegProcessFactory(listOf(FakeFfmpegProcess(stderr = listOf("a.mp4: No such file or directory"), exit = 1)))
        val ev = FfmpegRunner(fac, "/lib/x").run(TranscodeJob("a", "b", listOf("-c:v", "h264_mediacodec"), 10.0, true)).toList()
        assertEquals(1, fac.calls.size)
        assertEquals("Input file not found or inaccessible", (ev.last() as TranscodeEvent.Done).errorSummary)
    }

    @Test fun F5_softwareFailureNeverRetries() = runTest {
        val fac = FakeFfmpegProcessFactory(listOf(FakeFfmpegProcess(stderr = listOf("Invalid argument"), exit = 1)))
        val ev = FfmpegRunner(fac, "/x").run(TranscodeJob("a", "b", listOf("-c:v", "libx264"), 10.0, usedHardwareEncoder = false)).toList()
        assertEquals(1, fac.calls.size) // usedHardwareEncoder=false → no retry path
        assertEquals("Invalid ffmpeg arguments — check preset/raw args", (ev.last() as TranscodeEvent.Done).errorSummary)
    }

    @Test fun F6_cancelWritesQThenDestroys() = runTest {
        val p = FakeFfmpegProcess(stdout = emptyList(), exit = 0, exitDelayMs = 10_000)
        FfmpegRunner(FakeFfmpegProcessFactory(listOf(p)), "/lib/x").cancel(p, graceMs = 50)
        assertEquals("q", p.stdinWrites.single())
        assertEquals(1, p.destroyCount)
        assertEquals(1, p.destroyForciblyCount) // it never exited within the SIGTERM grace either
    }

    @Test fun F6b_cancelStopsAfterQWhenFfmpegExitsInTime() = runTest {
        val p = FakeFfmpegProcess(exit = 0, exitDelayMs = 10)
        FfmpegRunner(FakeFfmpegProcessFactory(listOf(p)), "/lib/x").cancel(p, graceMs = 50)
        assertEquals("q", p.stdinWrites.single())
        assertEquals(0, p.destroyCount)
        assertEquals(0, p.destroyForciblyCount)
    }

    // The grace must be ENFORCED: a plain blocking wait is not interrupted by withTimeoutOrNull, so a
    // wedged ffmpeg used to hang the cancel forever and never see SIGTERM. Escalation: q -> SIGTERM -> SIGKILL.
    @Test fun F8_cancelEscalatesOnAWedgedProcess() = runTest(timeout = 10.seconds) {
        val p = WedgedFfmpegProcess()
        FfmpegRunner(FakeFfmpegProcessFactory(emptyList()), "/lib/x").cancel(p, graceMs = 50, termGraceMs = 50)
        assertEquals(listOf("q"), p.stdinWrites)
        assertEquals(1, p.destroyCount)
        assertEquals(1, p.destroyForciblyCount)
    }

    // ffmpeg already exited (e.g. the output is being copied to storage): the stdin pipe is closed, so
    // 'q' throws. That must be a quiet no-op, not an exception out of the caller's coroutine.
    @Test fun F9_cancelOfAnAlreadyExitedProcessIsANoOp() = runTest {
        val p = FakeFfmpegProcess(exit = 0, stdinFailure = IOException("Stream closed"))
        FfmpegRunner(FakeFfmpegProcessFactory(listOf(p)), "/lib/x").cancel(p, graceMs = 50)
        assertEquals(0, p.destroyCount)
        assertEquals(0, p.destroyForciblyCount)
    }

    // Collector cancelled mid-run (service teardown): ffmpeg must not be left running as an orphan.
    @Test fun F10_cancellingTheCollectorKillsTheProcess() = runTest {
        val p = FakeFfmpegProcess(exit = 0, exitDelayMs = 60_000)
        val job = launch {
            FfmpegRunner(FakeFfmpegProcessFactory(listOf(p)), "/lib/x")
                .run(TranscodeJob("a", "b", listOf("-c:v", "libx264"), 10.0, false)).toList()
        }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(1, p.destroyForciblyCount)
    }

    @Test fun F7_errorLinesFlaggedIsError() = runTest {
        val fac = FakeFfmpegProcessFactory(listOf(FakeFfmpegProcess(stderr = listOf("just info", "Conversion failed!"), exit = 1)))
        val ev = FfmpegRunner(fac, "/x").run(TranscodeJob("a", "b", listOf("-c:v", "libx264"), 10.0, false)).toList()
        val logs = ev.filterIsInstance<TranscodeEvent.Log>()
        assertFalse(logs.first { it.line == "just info" }.isError)
        assertTrue(logs.first { it.line.contains("failed") }.isError)
    }
}
