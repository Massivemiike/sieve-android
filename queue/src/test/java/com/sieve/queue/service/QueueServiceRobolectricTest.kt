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

    // From API 35 the dataSync time limit arrives as onTimeout(startId, fgsType); an app that only overrides the API 34
    // one-argument shortService callback never sees it and is killed a few seconds later.
    @Test fun `the service handles the API 35 two-argument time-limit callback`() {
        val m = QueueService::class.java.getDeclaredMethod("onTimeout", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        assertTrue(m.declaringClass == QueueService::class.java)
    }
}
