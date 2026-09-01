package com.sieve.transcode

import com.sieve.transcode.runner.FfmpegProcess
import com.sieve.transcode.runner.FfmpegProcessFactory
import com.sieve.transcode.runner.FfmpegRunner
import com.sieve.transcode.runner.TranscodeEvent
import com.sieve.transcode.runner.TranscodeJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeFfmpegProcess(
    stdout: List<String> = emptyList(),
    stderr: List<String> = emptyList(),
    private val exit: Int = 0,
    private val exitDelayMs: Long = 0,
) : FfmpegProcess {
    override val stdout: Flow<String> = stdout.asFlow()
    override val stderr: Flow<String> = stderr.asFlow()
    val stdinWrites = mutableListOf<String>()
    var destroyCount = 0
    var destroyForciblyCount = 0
    override suspend fun writeStdin(text: String) { stdinWrites.add(text) }
    override fun destroy() { destroyCount++ }
    override fun destroyForcibly() { destroyForciblyCount++ }
    override suspend fun awaitExit(): Int {
        if (exitDelayMs > 0) delay(exitDelayMs)
        return exit
    }
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
        assertEquals(0, (ev.last() as TranscodeEvent.Done).exitCode)
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
    }

    @Test fun F7_errorLinesFlaggedIsError() = runTest {
        val fac = FakeFfmpegProcessFactory(listOf(FakeFfmpegProcess(stderr = listOf("just info", "Conversion failed!"), exit = 1)))
        val ev = FfmpegRunner(fac, "/x").run(TranscodeJob("a", "b", listOf("-c:v", "libx264"), 10.0, false)).toList()
        val logs = ev.filterIsInstance<TranscodeEvent.Log>()
        assertFalse(logs.first { it.line == "just info" }.isError)
        assertTrue(logs.first { it.line.contains("failed") }.isError)
    }
}
