package com.sieve.queue.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NextItemSelectorTest {
    private fun dl(id: String, s: DownloadStatus, pos: Long, pinned: Boolean = false) =
        QueueJob(id, JobSpec.Download("u", emptyList()), OutputRequest("d", "o"), status = s, position = pos, pinned = pinned)
    private fun tx(id: String, s: DownloadStatus, pos: Long) =
        QueueJob(id, JobSpec.Transcode("/i", emptyList(), 1.0, false), OutputRequest("d", "o"), status = s, position = pos)

    @Test fun `admits first queued download when a slot is free`() {
        val s = QueueState(jobs = listOf(dl("a", DownloadStatus.QUEUED, 0)), maxDownloads = 3)
        assertEquals(listOf("a"), NextItemSelector.select(s))
    }

    @Test fun `admits up to maxDownloads, respecting order`() {
        val s = QueueState(
            jobs = listOf(
                dl("a", DownloadStatus.QUEUED, 0), dl("b", DownloadStatus.QUEUED, 1),
                dl("c", DownloadStatus.QUEUED, 2), dl("d", DownloadStatus.QUEUED, 3),
            ),
            maxDownloads = 2,
        )
        assertEquals(listOf("a", "b"), NextItemSelector.select(s))
    }

    @Test fun `running and preparing both consume permits`() {
        val s = QueueState(
            jobs = listOf(
                dl("a", DownloadStatus.RUNNING, 0), dl("b", DownloadStatus.PREPARING, 1),
                dl("c", DownloadStatus.QUEUED, 2),
            ),
            maxDownloads = 2,
        )
        assertEquals(emptyList<String>(), NextItemSelector.select(s))
    }

    @Test fun `download and transcode permits are independent`() {
        val s = QueueState(
            jobs = listOf(
                dl("a", DownloadStatus.RUNNING, 0), dl("b", DownloadStatus.RUNNING, 1), dl("c", DownloadStatus.RUNNING, 2),
                tx("t1", DownloadStatus.QUEUED, 3),
            ),
            maxDownloads = 3, maxTranscodes = 1,
        )
        assertEquals(listOf("t1"), NextItemSelector.select(s))
    }

    @Test fun `transcode limit of one is honored`() {
        val s = QueueState(
            jobs = listOf(tx("t1", DownloadStatus.RUNNING, 0), tx("t2", DownloadStatus.QUEUED, 1)),
            maxTranscodes = 1,
        )
        assertEquals(emptyList<String>(), NextItemSelector.select(s))
    }

    @Test fun `global pause blocks all new starts`() {
        val s = QueueState(jobs = listOf(dl("a", DownloadStatus.QUEUED, 0)), globalPaused = true)
        assertEquals(emptyList<String>(), NextItemSelector.select(s))
    }

    @Test fun `pin does NOT change run order`() {
        val s = QueueState(
            jobs = listOf(
                dl("a", DownloadStatus.QUEUED, 0, pinned = false),
                dl("b", DownloadStatus.QUEUED, 1, pinned = true),
            ),
            maxDownloads = 1,
        )
        assertEquals(listOf("a"), NextItemSelector.select(s))
    }

    @Test fun `paused and terminal rows are never admitted`() {
        val s = QueueState(
            jobs = listOf(
                dl("a", DownloadStatus.PAUSED, 0), dl("b", DownloadStatus.COMPLETED, 1),
                dl("c", DownloadStatus.QUEUED, 2),
            ),
            maxDownloads = 3,
        )
        assertEquals(listOf("c"), NextItemSelector.select(s))
    }
}
