package com.sieve.queue.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueAggregatorTest {
    private fun job(id: String, s: DownloadStatus, speed: String? = null, size: Long? = null, frac: Float? = null) =
        QueueJob(
            id, JobSpec.Download("u", emptyList()), OutputRequest("d", "o"),
            status = s, progress = UnifiedProgress(fraction = frac, speed = speed, sizeBytes = size),
            title = "t$id",
        )

    @Test fun `counts running done queued`() {
        val sum = QueueAggregator.summarize(
            listOf(
                job("1", DownloadStatus.RUNNING, "1.0MiB/s", 100, 0.5f),
                job("2", DownloadStatus.QUEUED),
                job("3", DownloadStatus.COMPLETED),
                job("4", DownloadStatus.RUNNING, "2.0MiB/s", 200, 0.25f),
            ),
        )
        assertEquals(2, sum.running)
        assertEquals(1, sum.queued)
        assertEquals(1, sum.done)
        assertEquals(4, sum.total)
        assertEquals(3.0f, sum.totalSpeedMiB, 1e-3f) // 1 + 2
    }

    @Test fun `remaining bytes only counts running and paused with known size`() {
        val sum = QueueAggregator.summarize(
            listOf(
                job("1", DownloadStatus.RUNNING, "1.0MiB/s", 1000, 0.25f), // 750 remaining
                job("2", DownloadStatus.PAUSED, null, 400, 0.5f),          // 200 remaining
                job("3", DownloadStatus.RUNNING, "1.0MiB/s", null, 0.5f),  // unknown → skipped
            ),
        )
        assertEquals(950L, sum.remainingBytes)
    }

    @Test fun `active title is the first running job title`() {
        val sum = QueueAggregator.summarize(
            listOf(
                job("1", DownloadStatus.QUEUED),
                job("2", DownloadStatus.RUNNING, "1.0MiB/s", 100, 0.5f),
            ),
        )
        assertEquals("t2", sum.activeTitle)
    }

    @Test fun `empty queue is idle`() {
        assertTrue(QueueAggregator.summarize(emptyList()).isIdle)
    }

    @Test fun `idle when only terminal rows remain`() {
        val sum = QueueAggregator.summarize(listOf(job("1", DownloadStatus.COMPLETED), job("2", DownloadStatus.FAILED)))
        assertTrue(sum.isIdle)
    }
}
