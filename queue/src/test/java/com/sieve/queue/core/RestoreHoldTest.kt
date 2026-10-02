package com.sieve.queue.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreHoldTest {
    private fun dl(id: String, s: DownloadStatus) =
        QueueJob(id, JobSpec.Download("u$id", emptyList()), OutputRequest("d", "o"), status = s)

    @Test fun `a hold with nothing held is returned as it is`() {
        val hold = RestoreHold(migrated = true)
        assertSame(hold, hold.stillHeldIn(QueueState(jobs = listOf(dl("a", DownloadStatus.PAUSED)))))
    }

    @Test fun `a held row that is still paused stays held, and the same instance comes back`() {
        val hold = RestoreHold(migrated = true, heldIds = setOf("a", "b"))
        val state = QueueState(jobs = listOf(dl("a", DownloadStatus.PAUSED), dl("b", DownloadStatus.PAUSED), dl("other", DownloadStatus.PAUSED)))
        assertSame(hold, hold.stillHeldIn(state))
    }

    @Test fun `a held row that was resumed, finished, cancelled or removed is dropped`() {
        val hold = RestoreHold(migrated = true, heldIds = setOf("resumed", "running", "done", "cancelled", "gone", "kept"), bannerDismissed = true)
        val state = QueueState(
            jobs = listOf(
                dl("resumed", DownloadStatus.QUEUED), dl("running", DownloadStatus.RUNNING), dl("done", DownloadStatus.COMPLETED),
                dl("cancelled", DownloadStatus.CANCELLED), dl("kept", DownloadStatus.PAUSED),
            ),
        )
        assertEquals(RestoreHold(migrated = true, heldIds = setOf("kept"), bannerDismissed = true), hold.stillHeldIn(state))
    }

    @Test fun `the settled hold means no migration pending and nothing held`() {
        assertTrue(RestoreHold.SETTLED.migrated)
        assertTrue(RestoreHold.SETTLED.heldIds.isEmpty())
        assertFalse(RestoreHold.SETTLED.bannerDismissed)
        assertFalse("a fresh value is the un-migrated one", RestoreHold().migrated)
    }

    @Test fun `the in-memory store starts settled and keeps what it is given`() = runBlocking {
        val store = InMemoryRestoreHoldStore()
        assertEquals(RestoreHold.SETTLED, store.load())

        val hold = RestoreHold(migrated = true, heldIds = setOf("a"), bannerDismissed = true)
        store.save(hold)
        assertEquals(hold, store.load())
        assertEquals(RestoreHold(), InMemoryRestoreHoldStore(RestoreHold()).load())
    }
}
