package com.sieve.queue.service

import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueAggregator
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import com.sieve.transcode.runner.FfmpegProcess
import com.sieve.transcode.runner.FfmpegProcessFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * ffmpeg stuck in a native MediaCodec call (the Qualcomm codec service died under it, S26): alive, silent, deaf to `q` and
 * to SIGTERM; only SIGKILL ends it, and it dies of SIGABRT in its decoder thread when it finally goes.
 */
private class StuckFfmpeg : FfmpegProcess {
    val exit = CompletableDeferred<Int>()
    val stdinWrites = mutableListOf<String>()
    var sigterms = 0
    var sigkills = 0
    override val stdout: Flow<String> = emptyList<String>().asFlow()
    override val stderr: Flow<String> = emptyList<String>().asFlow()
    override suspend fun writeStdin(text: String) { stdinWrites += text }
    override fun destroy() { sigterms++ }
    override fun destroyForcibly() { sigkills++; exit.complete(134) }
    override suspend fun awaitExit(): Int = exit.await()
}

private class StuckFactory : FfmpegProcessFactory {
    val procs = CopyOnWriteArrayList<StuckFfmpeg>()
    override fun start(binaryPath: String, args: List<String>): FfmpegProcess = StuckFfmpeg().also { procs += it }
}

/** The whole chain the owner's thumb touches: Cancel -> QueueManager -> RealTranscodePort -> FfmpegRunner -> the process. */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueManagerHungTranscodeTest {
    private fun tx(id: String) = QueueJob(
        id,
        JobSpec.Transcode(
            inputPath = "/cache/tx-src-$id.mkv", presetArgs = listOf("-c:v", "h264_mediacodec", "-b:v", "3928k"),
            totalDurationSec = 19.0, usedHardwareEncoder = true,
        ),
        OutputRequest("Downloads/Sieve", "$id.mp4"),
    )

    @Test fun `the first Cancel tap on a hung hardware transcode ends the row CANCELLED, frees the slot and lets the service stop`() =
        runTest(timeout = 30.seconds) {
            val factory = StuckFactory()
            val port = RealTranscodePort("/lib/libsieveffmpeg.so", factory, NO_STALL) { null }
            val dl = FakeDownloadPort()
            val out = FakeOutputProvider()
            val m = QueueManager(JobDriver(dl, port), dl, port, InMemoryPersistence(), out, FakeClock(), initial = QueueState(maxTranscodes = 1))
            m.rehydrate()
            m.start(backgroundScope)
            m.enqueue(tx("t"))
            withContext(Dispatchers.Default) { withTimeout(10_000) { while (factory.procs.isEmpty()) delay(10) } }
            m.state.first { it.job("t")?.status == DownloadStatus.RUNNING }
            val ffmpeg = factory.procs.single()
            assertFalse("a hung run keeps the service alive", QueueAggregator.summarize(m.state.value.jobs).isIdle)

            m.cancel("t") // ONE tap
            m.state.first { it.job("t")?.status?.isTerminal == true }
            runCurrent()

            val row = m.state.value.job("t")!!
            assertEquals(DownloadStatus.CANCELLED, row.status)
            assertEquals(listOf("q"), ffmpeg.stdinWrites)
            assertEquals(1, ffmpeg.sigterms)
            assertEquals(1, ffmpeg.sigkills)
            assertEquals("no CPU respawn of a cancelled run", 1, factory.procs.size)
            assertTrue(out.finalized.isEmpty())
            assertTrue("the foreground service may stop", QueueAggregator.summarize(m.state.value.jobs).isIdle)
        }
}
