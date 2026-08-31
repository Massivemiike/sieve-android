package com.sieve.app

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sieve.engine.repo.EngineEvent
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueuePersistence
import com.sieve.queue.service.Clock
import com.sieve.queue.service.DownloadPort
import com.sieve.queue.service.JobDriver
import com.sieve.queue.service.QueueManager
import com.sieve.queue.service.RealTranscodePort
import com.sieve.storage.StorageModule
import com.sieve.transcode.args.ArgFinalizer
import com.sieve.transcode.args.BuilderEncoder
import com.sieve.transcode.args.FfmpegArgs
import com.sieve.transcode.args.FinalizeOptions
import com.sieve.transcode.runner.android.FfmpegBinary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class EndToEndSmokeTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private class MemPersistence : QueuePersistence {
        private val map = HashMap<String, QueueJob>()
        override suspend fun loadAll() = map.values.sortedBy { it.position }
        override suspend fun upsert(job: QueueJob) { map[job.id] = job }
        override suspend fun upsertAll(jobs: List<QueueJob>) { jobs.forEach { map[it.id] = it } }
        override suspend fun updateStatus(id: String, status: DownloadStatus) {}
        override suspend fun delete(id: String) { map.remove(id) }
        override suspend fun prune(cutoff: Long) = 0
    }

    private val noDownloadPort = object : DownloadPort {
        override fun download(id: String, url: String, args: List<String>): Flow<EngineEvent> = emptyFlow()
        override fun cancel(id: String) = true
    }

    private class TestClock : Clock { override fun nowMs() = 0L }

    private fun exec(cmd: List<String>): Int {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }
        return p.waitFor()
    }

    @Test
    fun transcodeThroughProductionWiredQueueCompletes() = runBlocking {
        val bin = FfmpegBinary.path(ctx)
        // 1. Generate a 1s test clip with the bundled ffmpeg.
        val clip = File(ctx.cacheDir, "e2e-src-${System.nanoTime()}.mp4")
        assertEquals(
            0,
            exec(listOf(bin, "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=320x240:rate=15", "-c:v", "libx264", "-pix_fmt", "yuv420p", clip.absolutePath)),
        )

        // 2. Wire a QueueManager exactly as production does (RealTranscodePort + StorageModule output).
        val prefs = PreferenceDataStoreFactory.create { File(ctx.cacheDir, "e2e-${System.nanoTime()}.preferences_pb") }
        val output = StorageModule.provideOutputLocationProvider(ctx, prefs) // no tree -> app-files/MediaStore sink
        val txPort = RealTranscodePort(bin)
        val manager = QueueManager(JobDriver(noDownloadPort, txPort), noDownloadPort, txPort, MemPersistence(), output, TestClock())
        manager.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))

        // 3. Enqueue a software H.264 720p transcode of the clip.
        val args = ArgFinalizer.finalize(
            FfmpegArgs.build("h264-720", BuilderEncoder.SOFTWARE, durationSec = 1.0),
            FinalizeOptions(requestedThreads = 2, emitThreads = true, crfOverride = 28),
        )
        val id = UUID.randomUUID().toString()
        manager.enqueue(
            QueueJob(
                id = id,
                spec = JobSpec.Transcode(clip.absolutePath, args, 1.0, usedHardwareEncoder = false),
                output = OutputRequest("Download/Sieve", "e2e-out.mp4"),
                title = "e2e",
            ),
        )

        // 4. It should reach COMPLETED (finalize streamed the work file into a sink).
        val terminal = withTimeout(90_000) {
            manager.state.first { s ->
                s.job(id)?.status == DownloadStatus.COMPLETED || s.job(id)?.status == DownloadStatus.FAILED
            }
        }
        assertEquals(DownloadStatus.COMPLETED, terminal.job(id)?.status)

        clip.delete()
        Unit
    }
}
