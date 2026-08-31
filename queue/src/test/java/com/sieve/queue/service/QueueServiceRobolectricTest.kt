package com.sieve.queue.service

import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueAggregator
import com.sieve.queue.core.QueueJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueServiceRobolectricTest {
    private fun job(s: DownloadStatus) =
        QueueJob("a", JobSpec.Download("u", emptyList()), OutputRequest("d", "o"), status = s)

    @Test fun `idle summary is true when all rows terminal`() {
        assertTrue(QueueAggregator.summarize(listOf(job(DownloadStatus.COMPLETED))).isIdle)
    }

    @Test fun `not idle while a job is queued`() {
        assertFalse(QueueAggregator.summarize(listOf(job(DownloadStatus.QUEUED))).isIdle)
    }
}
