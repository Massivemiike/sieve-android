package com.sieve.queue.service

import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import com.sieve.transcode.runner.TranscodeEvent
import com.sieve.transcode.runner.TranscodeJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscodeOutputThreadingTest {

    private fun tx(id: String) = QueueJob(
        id,
        JobSpec.Transcode(inputPath = "/in/$id.mkv", presetArgs = listOf("-c:v", "libx264"),
            totalDurationSec = 10.0, usedHardwareEncoder = false),
        OutputRequest("Downloads/Sieve", "%(title)s.%(ext)s"),
    )

    @Test
    fun `queue fills the TranscodeJob outputPath from the prepared work location`() = runTest {
        val captured = mutableListOf<TranscodeJob>()
        val txPort = object : TranscodePort {
            override fun run(id: String, job: TranscodeJob): Flow<TranscodeEvent> {
                captured += job
                return flow { emit(TranscodeEvent.Done(0, null, "")) }
            }
            override suspend fun cancel(id: String, graceMs: Long) {}
        }
        val dlPort = FakeDownloadPort()
        val out = FakeOutputProvider()
        val m = QueueManager(
            JobDriver(dlPort, txPort), dlPort, txPort, InMemoryPersistence(), out, FakeClock(),
            initial = QueueState(maxTranscodes = 1),
        )
        m.start(backgroundScope)
        m.enqueue(tx("t1"))
        m.state.first { it.job("t1")?.status == DownloadStatus.COMPLETED }

        // FakeOutputProvider.prepare returns PreparedOutput("/work/t1", "%(title)s.%(ext)s").
        assertEquals("/work/t1/%(title)s.%(ext)s", captured.single().outputPath)
        assertTrue(out.finalized.contains("t1"))
    }
}
