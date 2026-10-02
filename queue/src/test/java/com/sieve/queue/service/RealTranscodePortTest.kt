package com.sieve.queue.service

import com.sieve.transcode.runner.FfmpegProcess
import com.sieve.transcode.runner.FfmpegProcessFactory
import com.sieve.transcode.runner.TranscodeEvent
import com.sieve.transcode.runner.TranscodeJob
import com.sieve.transcode.runner.android.SourceVideoInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** An ffmpeg that quits cleanly on `q` (exit 0, like the real one), exits 143 on SIGTERM. */
private class FakeProc(stdout: List<String> = emptyList()) : FfmpegProcess {
    val exit = CompletableDeferred<Int>()
    val stdinWrites = mutableListOf<String>()
    var destroyed = 0
    var stdinFailure: IOException? = null
    override val stdout: Flow<String> = stdout.asFlow()
    override val stderr: Flow<String> = emptyList<String>().asFlow()
    override suspend fun writeStdin(text: String) {
        stdinFailure?.let { throw it }
        stdinWrites.add(text)
        exit.complete(0)
    }
    override fun destroy() { destroyed++; exit.complete(143) }
    override fun destroyForcibly() { exit.complete(137) }
    override suspend fun awaitExit(): Int = exit.await()
}

private class RecordingFactory(private val stdout: List<String> = emptyList(), private val onStart: () -> Unit = {}) : FfmpegProcessFactory {
    /** Keyed by the `-i` input path, so a test can tell which job's process is which. */
    val spawned = ConcurrentHashMap<String, FakeProc>()
    override fun start(binaryPath: String, args: List<String>): FfmpegProcess {
        onStart()
        val p = FakeProc(stdout)
        spawned[args[args.indexOf("-i") + 1]] = p
        return p
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RealTranscodePortTest {
    private fun job(input: String, durationSec: Double? = null) =
        TranscodeJob(input, "/work/out.mp4", listOf("-c:v", "libx264"), durationSec, false)

    private fun port(factory: FfmpegProcessFactory, probe: (String) -> SourceVideoInfo? = { null }) =
        RealTranscodePort("/lib/libsieveffmpeg.so", factory, probe)

    /** Real-time wait (the probe runs on real IO threads), not the test's virtual clock. */
    private suspend fun awaitSpawned(factory: RecordingFactory, n: Int) =
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (factory.spawned.size < n) delay(10) } }

    @Test fun `concurrent transcodes each cancel their own process`() = runTest(timeout = 20.seconds) {
        val factory = RecordingFactory()
        // A is still probing when B's run() starts, so A's process is spawned AFTER B's run() began —
        // the order that used to register A's process under B's id (one shared currentId).
        val bProbing = CountDownLatch(1)
        val p = port(factory) { path ->
            if (path == "/a") bProbing.await(5, TimeUnit.SECONDS) else bProbing.countDown()
            null
        }
        val doneA = async { p.run("A", job("/a")).toList() }
        val doneB = async { p.run("B", job("/b")).toList() }
        awaitSpawned(factory, 2)

        p.cancel("A", graceMs = 1_000)
        assertEquals(listOf("q"), factory.spawned["/a"]!!.stdinWrites)
        assertTrue("B must be untouched by A's cancel", factory.spawned["/b"]!!.stdinWrites.isEmpty())
        assertTrue(doneA.await().last() is TranscodeEvent.Done)

        p.cancel("B", graceMs = 1_000)
        assertEquals(listOf("q"), factory.spawned["/b"]!!.stdinWrites)
        assertTrue(doneB.await().last() is TranscodeEvent.Done)
    }

    @Test fun `cancel during the probe never spawns a process`() = runTest(timeout = 20.seconds) {
        val factory = RecordingFactory()
        val probeEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val p = port(factory) { probeEntered.countDown(); release.await(5, TimeUnit.SECONDS); null }
        val events = async { p.run("A", job("/a")).toList() }
        withContext(Dispatchers.Default) { probeEntered.await(5, TimeUnit.SECONDS) }

        p.cancel("A", graceMs = 100) // no process yet: must return, and remember
        release.countDown()

        assertEquals(0, factory.spawned.size)
        assertEquals(listOf<TranscodeEvent>(TranscodeEvent.Done(255, null, "")), events.await())
    }

    @Test fun `cancel racing the spawn stops the process it just missed`() = runTest(timeout = 20.seconds) {
        lateinit var p: RealTranscodePort
        // The cancel arrives inside spawn: after run() looked for a cancel, before it registered the process.
        val factory = RecordingFactory(onStart = { runBlocking { p.cancel("A", graceMs = 100) } })
        p = port(factory)
        val events = p.run("A", job("/a")).toList()

        val proc = factory.spawned["/a"]!!
        assertEquals(1, proc.destroyed)
        assertTrue(proc.stdinWrites.isEmpty())
        assertEquals(143, (events.last() as TranscodeEvent.Done).exitCode)
    }

    @Test fun `cancel on a process whose stdin is already closed does not throw`() = runTest(timeout = 20.seconds) {
        val factory = RecordingFactory()
        val p = port(factory)
        val events = async { p.run("A", job("/a")).toList() }
        awaitSpawned(factory, 1)
        val proc = factory.spawned["/a"]!!
        proc.stdinFailure = IOException("Stream closed")

        p.cancel("A", graceMs = 50) // used to throw IOException straight out of the caller's coroutine

        assertEquals(1, proc.destroyed) // 'q' could not be delivered, so it escalated to SIGTERM
        assertTrue(events.await().last() is TranscodeEvent.Done)
    }

    @Test fun `cancel after the process exited is a no-op while the output is still being saved`() = runTest(timeout = 20.seconds) {
        val factory = RecordingFactory()
        val p = port(factory)
        var writesWhenCancelled = -1
        val collected = launch {
            p.run("A", job("/a")).collect { ev ->
                // The queue keeps the job RUNNING (and its Cancel button live) while it copies the output
                // out; the collector is exactly there when Done arrives.
                if (ev is TranscodeEvent.Done) {
                    p.cancel("A", graceMs = 50)
                    writesWhenCancelled = factory.spawned["/a"]!!.stdinWrites.size
                }
            }
        }
        awaitSpawned(factory, 1)
        factory.spawned["/a"]!!.exit.complete(0)
        collected.join()

        assertEquals(0, writesWhenCancelled)
        assertEquals(0, factory.spawned["/a"]!!.destroyed)
    }

    @Test fun `an old cancel does not pre-cancel a later run of the same job id`() = runTest(timeout = 20.seconds) {
        val factory = RecordingFactory()
        val p = port(factory)
        p.cancel("A", graceMs = 50) // nothing running: nothing to remember either
        val events = async { p.run("A", job("/a")).toList() }
        awaitSpawned(factory, 1)
        val proc = factory.spawned["/a"]!!

        assertEquals(0, proc.destroyed)
        proc.exit.complete(0)
        assertEquals(0, (events.await().last() as TranscodeEvent.Done).exitCode)
    }

    private val halfway = listOf("out_time_us=60000000\nprogress=continue\n")

    @Test fun `progress is determinate from the probed duration when the spec has none`() = runTest(timeout = 20.seconds) {
        val factory = RecordingFactory(stdout = halfway)
        val p = port(factory) { SourceVideoInfo("video/avc", 1920, 1080, null, durationSec = 120.0) }
        val run = async { p.run("A", job("/a", durationSec = null)).toList() }
        awaitSpawned(factory, 1)
        factory.spawned["/a"]!!.exit.complete(0)
        val progress = run.await().filterIsInstance<TranscodeEvent.Progress>().single().progress
        assertEquals(0.5, progress.percent!!, 1e-9)
    }

    @Test fun `a duration already on the spec wins over the probe`() = runTest(timeout = 20.seconds) {
        val factory = RecordingFactory(stdout = halfway)
        val p = port(factory) { SourceVideoInfo("video/avc", 1920, 1080, null, durationSec = 120.0) }
        val run = async { p.run("A", job("/a", durationSec = 240.0)).toList() }
        awaitSpawned(factory, 1)
        factory.spawned["/a"]!!.exit.complete(0)
        val progress = run.await().filterIsInstance<TranscodeEvent.Progress>().single().progress
        assertEquals(0.25, progress.percent!!, 1e-9)
    }

    @Test fun `no duration anywhere stays indeterminate`() = runTest(timeout = 20.seconds) {
        val factory = RecordingFactory(stdout = halfway)
        val run = async { port(factory).run("A", job("/a", durationSec = null)).toList() }
        awaitSpawned(factory, 1)
        factory.spawned["/a"]!!.exit.complete(0)
        assertNull(run.await().filterIsInstance<TranscodeEvent.Progress>().single().progress.percent)
    }
}
