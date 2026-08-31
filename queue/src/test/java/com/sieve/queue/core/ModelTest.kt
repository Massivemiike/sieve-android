package com.sieve.queue.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test fun `terminal states are exactly the finished set`() {
        assertEquals(
            setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED),
            DownloadStatus.entries.filter { it.isTerminal }.toSet(),
        )
    }

    @Test fun `download job is resumable, transcode job is not`() {
        val dl = QueueJob("a", JobSpec.Download("u", listOf("-f", "best")), OutputRequest("t", "%(title)s.%(ext)s"))
        val tx = QueueJob("b", JobSpec.Transcode("/in.mkv", listOf("-c:v", "h264"), 10.0, false), OutputRequest("t", "out.mp4"))
        assertTrue(dl.resumable)
        assertFalse(tx.resumable)
        assertEquals(JobKind.DOWNLOAD, dl.kind)
        assertEquals(JobKind.TRANSCODE, tx.kind)
    }

    @Test fun `defaults are queued, zero progress, attempt zero`() {
        val j = QueueJob("a", JobSpec.Download("u", emptyList()), OutputRequest("t", "o"))
        assertEquals(DownloadStatus.QUEUED, j.status)
        assertEquals(0, j.attempt)
        assertNull(j.progress.fraction)
        assertNull(j.cancelReason)
        assertEquals(0L, j.position)
    }
}
