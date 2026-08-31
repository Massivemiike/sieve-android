package com.sieve.queue.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sieve.data.db.SieveDatabase
import com.sieve.engine.model.DownloadProgress
import com.sieve.engine.repo.EngineEvent
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import com.sieve.queue.persist.RoomQueuePersistence
import com.sieve.transcode.runner.TranscodeEvent
import com.sieve.transcode.runner.TranscodeJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke for the REAL QueueManager + REAL Room persistence + v1 output seam. Uses a scripted
 * download port (not the network) so it validates the Android-specific integration — the real Room DB,
 * the entity↔domain mapping, drain/persist, and process-death rehydrate — without flaky I/O.
 */
@RunWith(AndroidJUnit4::class)
class QueueSmokeTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun port(script: suspend FlowCollector<EngineEvent>.() -> Unit) = object : DownloadPort {
        override fun download(id: String, url: String, args: List<String>): Flow<EngineEvent> = flow { script() }
        override fun cancel(id: String): Boolean = true
    }

    private class NoopTranscodePort : TranscodePort {
        override fun run(id: String, job: TranscodeJob): Flow<TranscodeEvent> = emptyFlow()
        override suspend fun cancel(id: String, graceMs: Long) {}
    }

    private fun manager(dl: DownloadPort, persistence: RoomQueuePersistence): QueueManager {
        val tx = NoopTranscodePort()
        return QueueManager(
            JobDriver(dl, tx), dl, tx, persistence, RealOutputProviderV1(ctx), SystemClock(),
            initial = QueueState(maxDownloads = 1),
        )
    }

    private fun job(id: String) =
        QueueJob(id, JobSpec.Download("https://example/$id", listOf("-f", "best")), OutputRequest("Downloads/Sieve", "%(title)s.%(ext)s"))

    @Test fun enqueue_scripted_download_completes_and_persists() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, SieveDatabase::class.java).build()
        val persistence = RoomQueuePersistence(db.queueDao())
        val dl = port { emit(EngineEvent.Progress(DownloadProgress(0.5f))); emit(EngineEvent.Completed(0)) }
        val m = manager(dl, persistence)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
        m.start(scope)
        m.enqueue(job("dl-1"))
        withTimeout(10_000) { m.state.first { it.job("dl-1")?.status == DownloadStatus.COMPLETED } }
        assertEquals(DownloadStatus.COMPLETED, persistence.loadAll().first().status)
        scope.cancel()
        db.close()
    }

    @Test fun process_death_reconciles_and_resumes() {
        runBlocking {
            ctx.deleteDatabase("smoke.db")
            val id = "dl-resume"
            val db = Room.databaseBuilder(ctx, SieveDatabase::class.java, "smoke.db").build()
            val persistence = RoomQueuePersistence(db.queueDao())

            // A prior process persisted an in-flight (RUNNING) job — TaskMapping projects it to QUEUED on disk.
            persistence.upsert(job(id).copy(status = DownloadStatus.RUNNING))

            // A fresh manager on the same DB rehydrates the reconciled row and resumes it to completion.
            val dl = port { emit(EngineEvent.Completed(0)) }
            val m = manager(dl, persistence)
            m.rehydrate()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
            m.start(scope)
            withTimeout(15_000) { m.state.first { it.job(id)?.status == DownloadStatus.COMPLETED } }
            assertEquals(DownloadStatus.COMPLETED, m.state.value.job(id)!!.status)

            scope.cancel()
            db.close()
            ctx.deleteDatabase("smoke.db")
            Unit
        }
    }
}
