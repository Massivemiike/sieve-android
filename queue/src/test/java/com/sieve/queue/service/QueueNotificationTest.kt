package com.sieve.queue.service

import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import com.sieve.queue.core.UnifiedProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueNotificationTest {
    private fun dl(id: String, s: DownloadStatus, frac: Float? = null, title: String = "T") =
        QueueJob(
            id, JobSpec.Download("u", emptyList()), OutputRequest("d", "o"), status = s,
            progress = UnifiedProgress(fraction = frac), title = title,
        )

    @Test fun `running renders count active title and percent with Pause+Cancel`() {
        val model = QueueNotification.render(
            QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING, 0.63f, "Cats"), dl("b", DownloadStatus.QUEUED))),
        )
        assertTrue(model.title.contains("1"))
        assertTrue(model.title.contains("2"))
        assertTrue(model.text.contains("Cats"))
        assertTrue(model.text.contains("63"))
        assertEquals(63, model.progress)
        assertFalse(model.indeterminate)
        assertEquals(listOf(NotifAction.PAUSE, NotifAction.CANCEL), model.actions)
        assertEquals("a", model.actionTargetId)
    }

    @Test fun `paused renders Resume+Cancel and remaining count`() {
        val model = QueueNotification.render(
            QueueState(
                jobs = listOf(
                    dl("a", DownloadStatus.PAUSED, 0.4f), dl("b", DownloadStatus.QUEUED), dl("c", DownloadStatus.QUEUED),
                ),
            ),
        )
        assertTrue(model.title.contains("Paused"))
        assertEquals(listOf(NotifAction.RESUME, NotifAction.CANCEL), model.actions)
    }

    @Test fun `the count ignores finished rows restored from earlier sessions`() {
        val history = (1..50).map { dl("old$it", if (it % 2 == 0) DownloadStatus.COMPLETED else DownloadStatus.FAILED) }
        val model = QueueNotification.render(
            QueueState(jobs = history + dl("a", DownloadStatus.RUNNING, 0.1f, "Cats") + dl("b", DownloadStatus.QUEUED)),
        )
        assertEquals("Downloading 1 of 2", model.title)
    }

    @Test fun `paused rows are not counted as work in progress`() {
        // Rows held paused after an upgrade (or paused by the user) are not going to run until resumed.
        val model = QueueNotification.render(
            QueueState(jobs = listOf(dl("h1", DownloadStatus.PAUSED), dl("h2", DownloadStatus.PAUSED), dl("a", DownloadStatus.RUNNING, 0.2f))),
        )
        assertEquals("Downloading 1 of 1", model.title)
    }

    @Test fun `a download being prepared wins over a paused row`() {
        val model = QueueNotification.render(QueueState(jobs = listOf(dl("h", DownloadStatus.PAUSED), dl("new", DownloadStatus.PREPARING))))
        assertEquals("Preparing…", model.title)
        assertTrue(model.actions.isEmpty())
    }

    @Test fun `indeterminate when active job has null fraction`() {
        val model = QueueNotification.render(QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING, null))))
        assertTrue(model.indeterminate)
    }

    @Test fun `build produces an ongoing notification on the low channel`() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        QueueNotification.ensureChannel(ctx)
        val n = QueueNotification.build(ctx, QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING, 0.5f))))
        assertEquals(QueueNotification.CHANNEL_ID, n.channelId)
        assertTrue((n.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
    }

    @Test fun `pending intents use distinct request codes`() {
        assertNotEquals(
            QueueNotification.requestCode("a", NotifAction.PAUSE),
            QueueNotification.requestCode("a", NotifAction.CANCEL),
        )
    }
}
