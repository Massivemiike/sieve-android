package com.sieve.transcode

import com.sieve.transcode.runner.FfmpegProcess
import com.sieve.transcode.runner.FfmpegProcessFactory
import com.sieve.transcode.runner.FfmpegRunner
import com.sieve.transcode.runner.TranscodeEvent
import com.sieve.transcode.runner.TranscodeJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * What ffmpeg does when the Qualcomm codec service dies under its MediaCodec decoder (seen on the S26): it stays alive at
 * 100 % CPU, makes no progress, ignores `q` and SIGTERM, and only SIGKILL ends it. Cooperative suspends, so the runner's
 * clock is the test's virtual one.
 */
private class HungProcess(
    override val stdout: Flow<String> = emptyFlow(),
    override val stderr: Flow<String> = emptyFlow(),
) : FfmpegProcess {
    val exit = CompletableDeferred<Int>()
    val stdinWrites = mutableListOf<String>()
    var destroyCount = 0
    var destroyForciblyCount = 0
    override suspend fun writeStdin(text: String) { stdinWrites += text }
    override fun destroy() { destroyCount++ } // SIGTERM: ignored
    override fun destroyForcibly() { destroyForciblyCount++; exit.complete(137) }
    override suspend fun awaitExit(): Int = exit.await()
}

/** A process that exits by itself [exitAfterMs] of virtual time after its start (or never, when null). */
private class ScriptedProcess(
    override val stdout: Flow<String> = emptyFlow(),
    override val stderr: Flow<String> = emptyFlow(),
    private val exitAfterMs: Long? = 0,
    private val exitCode: Int = 0,
) : FfmpegProcess {
    private val killed = CompletableDeferred<Int>()
    var destroyForciblyCount = 0
    override suspend fun writeStdin(text: String) {}
    override fun destroy() {}
    override fun destroyForcibly() { destroyForciblyCount++; killed.complete(137) }
    override suspend fun awaitExit(): Int {
        if (exitAfterMs == null) return killed.await()
        return withTimeoutOrNull(exitAfterMs) { killed.await() } ?: exitCode
    }
}

private class QueueFactory(private val procs: List<FfmpegProcess>) : FfmpegProcessFactory {
    val calls = mutableListOf<List<String>>()
    override fun start(binaryPath: String, args: List<String>): FfmpegProcess {
        calls += args
        return procs[calls.size - 1]
    }
}

private fun progress(outUs: Long, frame: Long? = null, size: Long? = null) = buildString {
    if (frame != null) append("frame=$frame\n")
    if (size != null) append("total_size=$size\n")
    append("out_time_us=$outUs\nprogress=continue\n")
}

/** The watchdog, the hardware fallback, the cancel path and the log guard of [FfmpegRunner], against processes that misbehave. */
@OptIn(ExperimentalCoroutinesApi::class)
class FfmpegRunnerStallTest {

    private fun cpuJob(inputArgs: List<String> = emptyList()) =
        TranscodeJob("in.mkv", "out.mp4", listOf("-c:v", "libx264"), 19.0, usedHardwareEncoder = false, inputArgs = inputArgs)

    /** The S26 case: AV1 source decoded by MediaCodec, H.264 encoded by MediaCodec. */
    private fun hwJob() = TranscodeJob(
        "in.mkv", "out.mp4", listOf("-c:v", "h264_mediacodec", "-b:v", "3928k"), 19.0,
        usedHardwareEncoder = true, inputArgs = listOf("-c:v", "av1_mediacodec"),
    )

    private fun runner(vararg procs: FfmpegProcess) = FfmpegRunner(QueueFactory(procs.toList()), "/lib/libsieveffmpeg.so")

    private fun done(ev: List<TranscodeEvent>) = ev.last() as TranscodeEvent.Done

    // --- the stall watchdog, both sides of the bound ---------------------------------------------------------------

    @Test fun `a CPU run with no progress at all ends FAILED once the bound is up, not before`() = runTest(timeout = 30.seconds) {
        val p = HungProcess()
        val events = async { runner(p).run(cpuJob()).toList() }
        advanceTimeBy(FfmpegRunner.STALL_TIMEOUT_MS - 1_000)
        runCurrent()
        assertFalse("must not be stopped before the bound", events.isCompleted)
        assertEquals(0, p.destroyForciblyCount)

        advanceTimeBy(10_000) // the bound, then q (0.5 s) -> SIGTERM (0.5 s) -> SIGKILL
        runCurrent()
        assertTrue(events.isCompleted)
        val d = done(events.await())
        assertEquals(FfmpegRunner.EXIT_STALLED, d.exitCode)
        assertEquals("ffmpeg stopped making progress", d.errorSummary)
        assertEquals(listOf("q"), p.stdinWrites)
        assertEquals(1, p.destroyCount)
        assertEquals(1, p.destroyForciblyCount)
    }

    @Test fun `a slow encode that keeps advancing is never stopped, however long it runs`() = runTest(timeout = 30.seconds) {
        // AV1 4K at 0.1x: a progress advance every 100 s (far slower than ffmpeg's real ~0.5 s) for a whole hour.
        val blocks = flow { for (i in 1..36) { delay(100_000); emit(progress(i * 10_000_000L)) } }
        val p = ScriptedProcess(stdout = blocks, exitAfterMs = 3_601_000)
        val events = runner(p).run(cpuJob()).toList()
        assertEquals(0, done(events).exitCode)
        assertNull(done(events).errorSummary)
        assertEquals(36, events.count { it is TranscodeEvent.Progress })
        assertEquals(0, p.destroyForciblyCount)
    }

    @Test fun `just under the bound between two advances is fine`() = runTest(timeout = 30.seconds) {
        val blocks = flow {
            delay(FfmpegRunner.STALL_TIMEOUT_MS - 2_000); emit(progress(1_000_000))
            delay(FfmpegRunner.STALL_TIMEOUT_MS - 2_000); emit(progress(2_000_000))
        }
        val p = ScriptedProcess(stdout = blocks, exitAfterMs = 2 * FfmpegRunner.STALL_TIMEOUT_MS)
        assertEquals(0, done(runner(p).run(cpuJob()).toList()).exitCode)
    }

    @Test fun `progress blocks that only repeat the start-up numbers do not count as progress`() = runTest(timeout = 30.seconds) {
        // ffmpeg's main thread keeps printing while a codec is wedged under it: frame=0, out_time=N/A, every 0.5 s.
        val blocks = flow { while (true) { delay(500); emit("frame=0\ntotal_size=0\nout_time_us=N/A\nspeed=N/A\nprogress=continue\n") } }
        val events = async { runner(HungProcess(stdout = blocks)).run(cpuJob()).toList() }
        advanceTimeBy(FfmpegRunner.STALL_TIMEOUT_MS + 10_000)
        runCurrent()
        assertTrue(events.isCompleted)
        assertEquals(FfmpegRunner.EXIT_STALLED, done(events.await()).exitCode)
    }

    @Test fun `an out_time that stopped moving is a stall whatever else is printed`() = runTest(timeout = 30.seconds) {
        val blocks = flow { emit(progress(5_000_000)); while (true) { delay(500); emit(progress(5_000_000)) } }
        val events = async { runner(HungProcess(stdout = blocks)).run(cpuJob()).toList() }
        advanceTimeBy(FfmpegRunner.STALL_TIMEOUT_MS + 10_000)
        runCurrent()
        assertTrue(events.isCompleted)
        assertEquals(FfmpegRunner.EXIT_STALLED, done(events.await()).exitCode)
    }

    @Test fun `stderr output is not progress, however much of it there is`() = runTest(timeout = 30.seconds) {
        val noise = flow { var i = 0; while (true) { delay(10); emit("[h264_mediacodec @ 0x7b] Failed to dequeue output buffer ${i++}") } }
        val events = async { runner(HungProcess(stderr = noise)).run(cpuJob()).toList() }
        advanceTimeBy(FfmpegRunner.STALL_TIMEOUT_MS + 10_000)
        runCurrent()
        assertTrue(events.isCompleted)
        assertEquals(FfmpegRunner.EXIT_STALLED, done(events.await()).exitCode)
    }

    @Test fun `a frame counter or an output size that advances counts when out_time is not available yet`() = runTest(timeout = 30.seconds) {
        val frames = flow { for (i in 1..400) { delay(1_000); emit("frame=$i\nout_time_us=N/A\nprogress=continue\n") } }
        val p = ScriptedProcess(stdout = frames, exitAfterMs = 400_000)
        assertEquals(0, done(runner(p).run(cpuJob()).toList()).exitCode)
        val sizes = flow { for (i in 1..400) { delay(1_000); emit("total_size=${i * 1000}\nout_time_us=N/A\nprogress=continue\n") } }
        val q = ScriptedProcess(stdout = sizes, exitAfterMs = 400_000)
        assertEquals(0, done(runner(q).run(cpuJob()).toList()).exitCode)
    }

    @Test fun `the watchdog bound is two minutes`() {
        // Pinned on purpose: 4K software encodes spend tens of seconds before their first packet, and a +faststart rewrite
        // of a multi-GB output after their last. Shortening it needs both of those re-measured.
        assertEquals(120_000L, FfmpegRunner.STALL_TIMEOUT_MS)
    }

    // --- the first-progress bound of a hardware encode ---------------------------------------------------------------

    @Test fun `the first-progress bound is twenty seconds, the runner's default, and shorter than the watchdog`() {
        // Pinned on purpose: on the S26 healthy MediaCodec runs advance within about a second (a 19 s clip is saved in 0.7-0.8 s) and
        // the hang cost two minutes. Raising it gives that back; lowering it needs the slowest healthy start re-measured.
        assertEquals(20_000L, FfmpegRunner.FIRST_PROGRESS_TIMEOUT_MS)
        assertEquals(FfmpegRunner.FIRST_PROGRESS_TIMEOUT_MS, FfmpegRunner.Limits().firstProgressTimeoutMs)
        assertTrue(FfmpegRunner.FIRST_PROGRESS_TIMEOUT_MS < FfmpegRunner.STALL_TIMEOUT_MS)
    }

    @Test fun `a hardware run that prints no progress at all is stalled after the first-progress bound and retried on the CPU`() =
        runTest(timeout = 30.seconds) {
            val hung = HungProcess()
            val ok = ScriptedProcess(stdout = flowOf2(progress(9_000_000)), exitAfterMs = 10)
            val fac = QueueFactory(listOf(hung, ok))
            val events = async { FfmpegRunner(fac, "/x").run(hwJob()).toList() }

            advanceTimeBy(FfmpegRunner.FIRST_PROGRESS_TIMEOUT_MS - 1_000)
            runCurrent()
            assertEquals("not stopped before the bound", 1, fac.calls.size)
            assertEquals(0, hung.destroyForciblyCount)
            assertTrue(hung.stdinWrites.isEmpty())

            advanceTimeBy(5_000) // the bound, then q (0.5 s) -> SIGTERM (0.5 s) -> SIGKILL, then the CPU run
            runCurrent()
            assertTrue("done long before the two-minute watchdog would even have fired (at ${currentTime} ms)", events.isCompleted)
            val ev = events.await()
            assertEquals(2, fac.calls.size)
            assertEquals(listOf("q"), hung.stdinWrites)
            assertEquals(1, hung.destroyCount)
            assertEquals(1, hung.destroyForciblyCount)
            assertTrue(fac.calls[0].contains("h264_mediacodec"))
            assertTrue(fac.calls[1].contains("libx264") && !fac.calls[1].contains("h264_mediacodec"))
            assertEquals(0, done(ev).exitCode)
            assertEquals(
                listOf("Hardware codec stopped making progress, retrying on software encoder"),
                ev.filterIsInstance<TranscodeEvent.Log>().map { it.line },
            )
        }

    @Test fun `a hardware run that advanced and then stalls keeps the two-minute bound`() = runTest(timeout = 30.seconds) {
        // The first block comes at 1.5 s, far inside the first-progress bound; the silence after it is a mid-run stall.
        val hung = HungProcess(stdout = flow { delay(1_500); emit(progress(1_000_000)); awaitCancellation() })
        val ok = ScriptedProcess(stdout = flowOf2(progress(9_000_000)), exitAfterMs = 10)
        val fac = QueueFactory(listOf(hung, ok))
        val events = async { FfmpegRunner(fac, "/x").run(hwJob()).toList() }

        advanceTimeBy(FfmpegRunner.STALL_TIMEOUT_MS) // 118 s of silence since the block: the ordinary bound is not up
        runCurrent()
        assertEquals("a mid-run stall is not held to the first-progress bound", 1, fac.calls.size)
        assertEquals(0, hung.destroyForciblyCount)

        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(events.isCompleted)
        val ev = events.await()
        assertEquals(2, fac.calls.size)
        assertEquals(1, hung.destroyForciblyCount)
        assertEquals(0, done(ev).exitCode)
        assertEquals("Hardware codec stopped making progress, retrying on software encoder", ev.filterIsInstance<TranscodeEvent.Log>().single().line)
    }

    @Test fun `a CPU run with no progress for longer than the first-progress bound is not stalled early`() = runTest(timeout = 30.seconds) {
        val p = HungProcess()
        val events = async { runner(p).run(cpuJob()).toList() }
        advanceTimeBy(3 * FfmpegRunner.FIRST_PROGRESS_TIMEOUT_MS)
        runCurrent()
        assertFalse("a software encode may take tens of seconds to its first packet", events.isCompleted)
        assertEquals(0, p.destroyForciblyCount)
        assertTrue(p.stdinWrites.isEmpty())

        advanceTimeBy(FfmpegRunner.STALL_TIMEOUT_MS) // only the ordinary bound ends it
        runCurrent()
        assertTrue(events.isCompleted)
        assertEquals(FfmpegRunner.EXIT_STALLED, done(events.await()).exitCode)
    }

    @Test fun `a CPU encode that takes 45 s to its first progress is left alone`() = runTest(timeout = 30.seconds) {
        // A 4K software encode on a phone: tens of seconds of look-ahead before the first packet.
        val blocks = flow { delay(45_000); emit(progress(1_000_000)); delay(1_000); emit(progress(2_000_000)) }
        val p = ScriptedProcess(stdout = blocks, exitAfterMs = 50_000)
        val ev = runner(p).run(cpuJob()).toList()
        assertEquals(0, done(ev).exitCode)
        assertEquals(0, p.destroyForciblyCount)
        assertEquals(2, ev.count { it is TranscodeEvent.Progress })
    }

    @Test fun `a hardware decoder behind a CPU encoder keeps the two-minute bound before its first progress`() = runTest(timeout = 30.seconds) {
        // Only the ENCODER being hardware promises a first frame within seconds; the CPU encoder behind a MediaCodec decoder may not.
        val fac = QueueFactory(listOf(HungProcess(), ScriptedProcess(exitCode = 0)))
        val job = cpuJob(inputArgs = listOf("-c:v", "hevc_mediacodec"))
        val events = async { FfmpegRunner(fac, "/x").run(job).toList() }
        advanceTimeBy(FfmpegRunner.STALL_TIMEOUT_MS - 1_000)
        runCurrent()
        assertEquals("not stopped at ${currentTime} ms", 1, fac.calls.size)

        advanceTimeBy(10_000) // the ordinary bound still ends it, and the decoder is retried in software
        runCurrent()
        assertTrue(events.isCompleted)
        assertEquals(2, fac.calls.size)
        assertEquals(0, done(events.await()).exitCode)
    }

    @Test fun `a hardware run that seeks with -ss keeps the two-minute bound before its first progress`() = runTest(timeout = 30.seconds) {
        // FfmpegArgs puts -ss after -i: ffmpeg decodes and drops everything before it, and nothing advances meanwhile.
        val seeking = hwJob().copy(presetArgs = listOf("-ss", "300", "-c:v", "h264_mediacodec", "-b:v", "3928k"))
        val slowStart = ScriptedProcess(stdout = flow { delay(60_000); emit(progress(1_000_000)) }, exitAfterMs = 65_000)
        val fac = QueueFactory(listOf(slowStart))
        val ev = FfmpegRunner(fac, "/x").run(seeking).toList()
        assertEquals("no retry", 1, fac.calls.size)
        assertEquals(0, slowStart.destroyForciblyCount)
        assertEquals(0, done(ev).exitCode)
        assertEquals(1, ev.count { it is TranscodeEvent.Progress })
    }

    @Test fun `the CPU retry after a first-progress stall is not held to the short bound`() = runTest(timeout = 30.seconds) {
        // The AV1 decoder stays on the retry (this ffmpeg has no software AV1 decoder), so the retry still involves hardware; its CPU
        // encoder may need tens of seconds to its first packet, and a second stall would fail the job for good.
        val hung = HungProcess()
        val slowRetry = ScriptedProcess(stdout = flow { delay(45_000); emit(progress(1_000_000)) }, exitAfterMs = 50_000)
        val fac = QueueFactory(listOf(hung, slowRetry))
        val ev = FfmpegRunner(fac, "/x").run(hwJob()).toList()
        assertEquals(2, fac.calls.size)
        assertEquals("av1_mediacodec", fac.calls[1][fac.calls[1].indexOf("-c:v") + 1])
        assertEquals(0, slowRetry.destroyForciblyCount)
        assertEquals(0, done(ev).exitCode)
        assertEquals(1, ev.filterIsInstance<TranscodeEvent.Log>().size)
    }

    // --- hardware fallback ------------------------------------------------------------------------------------------

    @Test fun `a stalled hardware run falls back to the CPU encoder, once, and finishes there`() = runTest(timeout = 30.seconds) {
        val hung = HungProcess()
        val ok = ScriptedProcess(stdout = flowOf2(progress(9_000_000)), exitAfterMs = 10)
        val fac = QueueFactory(listOf(hung, ok))
        val events = async { FfmpegRunner(fac, "/x").run(hwJob()).toList() }
        advanceTimeBy(FfmpegRunner.STALL_TIMEOUT_MS + 10_000)
        advanceUntilIdle()
        val ev = events.await()

        assertEquals(2, fac.calls.size)
        assertEquals(1, hung.destroyForciblyCount)
        val hw = fac.calls[0]
        val sw = fac.calls[1]
        assertTrue(hw.contains("h264_mediacodec"))
        assertTrue(sw.contains("libx264") && !sw.contains("h264_mediacodec"))
        // AV1 has no software decoder in this ffmpeg build: the decoder stays.
        assertEquals("av1_mediacodec", sw[sw.indexOf("-c:v") + 1])
        assertEquals(0, done(ev).exitCode)
        assertEquals(
            listOf("Hardware codec stopped making progress, retrying on software encoder"),
            ev.filterIsInstance<TranscodeEvent.Log>().map { it.line },
        )
    }

    private fun flowOf2(text: String): Flow<String> = listOf(text).asFlow()

    @Test fun `the fallback happens once, a second stall on the CPU path is a plain FAILED`() = runTest(timeout = 30.seconds) {
        val a = HungProcess()
        val b = HungProcess()
        val fac = QueueFactory(listOf(a, b))
        val events = async { FfmpegRunner(fac, "/x").run(hwJob()).toList() }
        advanceTimeBy(2 * (FfmpegRunner.STALL_TIMEOUT_MS + 10_000))
        advanceUntilIdle()
        val ev = events.await()
        assertEquals(2, fac.calls.size)
        assertEquals(FfmpegRunner.EXIT_STALLED, done(ev).exitCode)
        assertEquals("ffmpeg stopped making progress", done(ev).errorSummary)
    }

    @Test fun `a hardware run killed by a crash signal falls back to the CPU`() = runTest(timeout = 30.seconds) {
        for (code in FfmpegRunner.CRASH_EXIT_CODES) {
            val fac = QueueFactory(listOf(ScriptedProcess(exitCode = code), ScriptedProcess(exitCode = 0)))
            val ev = FfmpegRunner(fac, "/x").run(hwJob()).toList()
            assertEquals("exit $code", 2, fac.calls.size)
            assertEquals(0, done(ev).exitCode)
            assertTrue(ev.filterIsInstance<TranscodeEvent.Log>().single().line.startsWith("Hardware codec crashed, retrying"))
        }
    }

    @Test fun `a SIGKILL or SIGTERM exit is a stop, not a hardware crash`() = runTest(timeout = 30.seconds) {
        for (code in listOf(137, 143, 130, 255, 1)) {
            val fac = QueueFactory(listOf(ScriptedProcess(exitCode = code)))
            val ev = FfmpegRunner(fac, "/x").run(hwJob()).toList()
            assertEquals("exit $code", 1, fac.calls.size)
            assertEquals(code, done(ev).exitCode)
        }
    }

    @Test fun `a run the caller asked to stop is never retried, even when it dies of SIGABRT`() = runTest(timeout = 30.seconds) {
        // A cancelled hung ffmpeg dies with SIGABRT in its decoder thread (seen on the S26): that is the cancel, not a crash.
        val fac = QueueFactory(listOf(ScriptedProcess(exitCode = 134)))
        val ev = FfmpegRunner(fac, "/x").run(hwJob(), stopRequested = { true }).toList()
        assertEquals(1, fac.calls.size)
        assertEquals(134, done(ev).exitCode)
        assertTrue(ev.none { it is TranscodeEvent.Log && it.line.contains("retrying") })
    }

    @Test fun `a CPU-only run that crashes is just FAILED`() = runTest(timeout = 30.seconds) {
        val fac = QueueFactory(listOf(ScriptedProcess(exitCode = 134)))
        val ev = FfmpegRunner(fac, "/x").run(cpuJob()).toList()
        assertEquals(1, fac.calls.size)
        assertEquals(134, done(ev).exitCode)
    }

    @Test fun `a stalled hardware DECODER on a CPU encode is retried, a software-capable decoder is dropped`() = runTest(timeout = 30.seconds) {
        val fac = QueueFactory(listOf(HungProcess(), ScriptedProcess(exitCode = 0)))
        val job = cpuJob(inputArgs = listOf("-ss", "5", "-c:v", "h264_mediacodec"))
        val events = async { FfmpegRunner(fac, "/x").run(job).toList() }
        advanceTimeBy(FfmpegRunner.STALL_TIMEOUT_MS + 10_000)
        advanceUntilIdle()
        val ev = events.await()
        assertEquals(0, done(ev).exitCode)
        val retry = fac.calls[1]
        assertFalse(retry.contains("h264_mediacodec"))
        assertEquals(listOf("-ss", "5"), retry.subList(retry.indexOf("-ss"), retry.indexOf("-i")))
        assertEquals("Hardware codec stopped making progress, retrying", ev.filterIsInstance<TranscodeEvent.Log>().single().line)
    }

    @Test fun `the software input args keep the AV1 decoder and drop only what software can decode`() {
        assertEquals(listOf("-c:v", "av1_mediacodec"), FfmpegRunner.demoteInputToSoftware(listOf("-c:v", "av1_mediacodec")))
        assertEquals(listOf("-ss", "5"), FfmpegRunner.demoteInputToSoftware(listOf("-ss", "5", "-c:v", "hevc_mediacodec")))
        assertEquals(listOf("-c:v", "libx264"), FfmpegRunner.demoteInputToSoftware(listOf("-c:v", "libx264")))
        assertEquals(emptyList<String>(), FfmpegRunner.demoteInputToSoftware(emptyList()))
    }

    // --- cancel -----------------------------------------------------------------------------------------------------

    @Test fun `ONE cancel ends a hung run within a few seconds and the run reports it`() = runTest(timeout = 30.seconds) {
        val p = HungProcess()
        val r = runner(p)
        val events = async { r.run(hwJob(), stopRequested = { true }).toList() }
        runCurrent()

        val t0 = currentTime
        assertTrue(r.cancel(p, graceMs = 3_000))
        advanceUntilIdle()

        assertTrue("q, then SIGTERM, then SIGKILL, in that order, once each", p.stdinWrites == listOf("q") && p.destroyCount == 1 && p.destroyForciblyCount == 1)
        assertTrue("took ${currentTime - t0} ms of the wedged process's time", currentTime - t0 <= 3_000 + FfmpegRunner.TERM_GRACE_MS + 100)
        val d = done(events.await())
        assertEquals(137, d.exitCode) // reported as the kill it was, not as a stall or a crash
        assertEquals(1, events.await().count { it is TranscodeEvent.Done })
    }

    @Test fun `cancel reports false when the process outlives even SIGKILL`() = runTest(timeout = 30.seconds) {
        val immortal = object : FfmpegProcess {
            override val stdout: Flow<String> = emptyFlow()
            override val stderr: Flow<String> = emptyFlow()
            override suspend fun writeStdin(text: String) {}
            override fun destroy() {}
            override fun destroyForcibly() {}
            override suspend fun awaitExit(): Int = awaitCancellation()
        }
        assertFalse(runner().cancel(immortal, graceMs = 10, termGraceMs = 10))
    }

    // --- the pipes --------------------------------------------------------------------------------------------------

    /** A pipe that never reaches EOF and a read that cannot be cancelled: a blocking read(2). */
    private class StuckReader : FfmpegProcess {
        val release = CountDownLatch(1)
        override val stdout: Flow<String> = flow<String> { release.await(30, TimeUnit.SECONDS) }.flowOn(Dispatchers.IO)
        override val stderr: Flow<String> = emptyFlow()
        override suspend fun writeStdin(text: String) {}
        override fun destroy() {}
        override fun destroyForcibly() {}
        override suspend fun awaitExit(): Int = 137
    }

    @Test fun `a reader that never returns does not hold the run open once the process is gone`() = runTest(timeout = 30.seconds) {
        val p = StuckReader()
        try {
            val events = async { runner(p).run(cpuJob()).toList() }
            withContext(Dispatchers.Default) { Thread.sleep(300) } // let the real IO thread reach its read
            advanceTimeBy(FfmpegRunner.READER_DRAIN_MS + 1_000)
            runCurrent()
            val ended = events.isCompleted
            p.release.countDown()
            if (!ended) events.cancelAndJoin()
            assertTrue("the run must end although a pipe reader is stuck", ended)
            assertEquals(137, done(events.await()).exitCode)
        } finally {
            p.release.countDown()
        }
    }

    @Test fun `a reader that fails fails the run, as it always did`() = runTest(timeout = 30.seconds) {
        val boom = flow<String> { throw IllegalStateException("pipe exploded") }
        val result = runCatching { runner(ScriptedProcess(stdout = boom)).run(cpuJob()).toList() }
        assertEquals("pipe exploded", result.exceptionOrNull()?.message)
    }

    // --- the log flood ----------------------------------------------------------------------------------------------

    @Test fun `a thousand identical complaints a second cost a handful of events and a tiny tail`() = runTest(timeout = 30.seconds) {
        val line = "[av1_mediacodec @ 0x7b5c] Invalid to call at Released state; only valid in executing state"
        val flood = (1..200_000).asSequence().map { line }.asFlow()
        val events = runner(ScriptedProcess(stderr = flood, exitCode = 1)).run(cpuJob()).toList()
        val logs = events.filterIsInstance<TranscodeEvent.Log>().map { it.line }
        assertEquals(listOf(line, "Last message repeated 199999 more times"), logs)
        val d = done(events)
        assertTrue(d.stderrTail.lines().size <= 30)
    }

    @Test fun `a flood of DIFFERENT lines is paced and the tails stay bounded`() = runTest(timeout = 30.seconds) {
        val flood = (1..300_000).asSequence().map { "[h264_mediacodec @ 0x7b5c] state $it: Invalid to call at Released state" }.asFlow()
        val events = runner(ScriptedProcess(stderr = flood, exitCode = 1)).run(cpuJob()).toList()
        val logs = events.filterIsInstance<TranscodeEvent.Log>().map { it.line }
        assertTrue("${logs.size} log events for 300000 lines", logs.size < FfmpegRunner.LOG_BURST_LINES + 2_000)
        assertTrue(logs.any { it.endsWith("skipped (too many, too fast)") })
        val d = done(events)
        assertTrue(d.stderrTail.lines().size <= 30)
        assertNotNull(d.errorSummary)
    }

    @Test fun `a slow consumer loses the oldest events, never the Done`() = runTest(timeout = 60.seconds) {
        val chunks = (1..5_000).asSequence().map { progress(it * 1_000L) }.asFlow()
        val received = mutableListOf<TranscodeEvent>()
        runner(ScriptedProcess(stdout = chunks, exitAfterMs = 0)).run(cpuJob()).collect {
            delay(5)
            received += it
        }
        assertTrue(received.last() is TranscodeEvent.Done)
        assertTrue("received ${received.size} of 5001", received.size < 1_000)
    }

    @Test fun `a collector that goes away kills the process and stops its readers`() = runTest(timeout = 30.seconds) {
        val p = ScriptedProcess(stdout = flow { awaitCancellation() }, exitAfterMs = null)
        val job = launch { runner(p).run(cpuJob()).toList() }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(1, p.destroyForciblyCount)
    }
}
