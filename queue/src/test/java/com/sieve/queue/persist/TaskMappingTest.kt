package com.sieve.queue.persist

import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.UnifiedProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskMappingTest {
    @Test fun `running download persists as queued with progress 0`() {
        val job = QueueJob(
            "dl-1", JobSpec.Download("u", listOf("-f", "best")), OutputRequest("Downloads/Sieve", "%(title)s.%(ext)s"),
            status = DownloadStatus.RUNNING, progress = UnifiedProgress(fraction = 0.5f, speed = "2MiB/s"),
            title = "T", logsTail = listOf("noise"), speedHistory = listOf(1f, 2f),
        )
        val e = TaskMapping.toEntity(job)
        assertEquals("QUEUED", e.status)   // projection; column is a String
        assertEquals(0f, e.progress, 1e-4f)
        assertEquals("DOWNLOAD", e.kind)
        assertEquals(listOf("-f", "best"), e.args)

        val back = TaskMapping.toDomain(e)
        assertEquals(DownloadStatus.QUEUED, back.status)
        assertTrue(back.logsTail.isEmpty())
        assertTrue(back.spec is JobSpec.Download)
    }

    @Test fun `completed download keeps progress 1`() {
        val job = QueueJob(
            "dl-1", JobSpec.Download("u", emptyList()), OutputRequest("d", "o"),
            status = DownloadStatus.COMPLETED, progress = UnifiedProgress(fraction = 1f), completedAt = 999,
        )
        val e = TaskMapping.toEntity(job)
        assertEquals("COMPLETED", e.status)
        assertEquals(1f, e.progress, 1e-4f)
        assertEquals(999L, e.completedAt)
    }

    @Test fun `transcode spec round trips`() {
        val job = QueueJob(
            "tx-1", JobSpec.Transcode("/in.mkv", listOf("-c:v", "h264"), 12.5, true),
            OutputRequest("d", "out.mp4"), status = DownloadStatus.RUNNING,
        )
        val e = TaskMapping.toEntity(job)
        assertEquals("TRANSCODE", e.kind)
        assertEquals("/in.mkv", e.inputPath)
        assertEquals(12.5, e.totalDurationSec!!, 1e-6)
        val spec = TaskMapping.toDomain(e).spec as JobSpec.Transcode
        assertEquals(listOf("-c:v", "h264"), spec.presetArgs)
        assertTrue(spec.usedHardwareEncoder)
    }

    @Test fun `paused survives as paused (not queued) — resume is user-driven`() {
        val job = QueueJob("dl-1", JobSpec.Download("u", emptyList()), OutputRequest("d", "o"), status = DownloadStatus.PAUSED)
        assertEquals("PAUSED", TaskMapping.toEntity(job).status)
    }

    @Test fun `unknown persisted status decodes to QUEUED`() {
        val e = TaskMapping.toEntity(QueueJob("a", JobSpec.Download("u", emptyList()), OutputRequest("d", "o")))
            .copy(status = "GARBAGE")
        assertEquals(DownloadStatus.QUEUED, TaskMapping.toDomain(e).status)
    }
}
