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

    private fun tx(id: String, s: DownloadStatus, frac: Float? = null, title: String = "Clip.mkv") =
        QueueJob(
            id, JobSpec.Transcode("/in.mkv", emptyList(), 10.0, false), OutputRequest("d", "o"), status = s,
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

    // ---- the running title says what is running (a transcode used to read "Downloading 1 of 1") ----

    @Test fun `only downloads in progress still read Downloading`() {
        val model = QueueNotification.render(
            QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING, 0.3f), dl("b", DownloadStatus.PREPARING), dl("c", DownloadStatus.QUEUED))),
        )
        assertEquals("Downloading 1 of 3", model.title)
    }

    @Test fun `a lone running transcode reads Transcoding not Downloading`() {
        val model = QueueNotification.render(QueueState(jobs = listOf(tx("t", DownloadStatus.RUNNING, 0.4f, "Clip.mkv"))))
        assertEquals("Transcoding 1 of 1", model.title)
        assertTrue(model.text.contains("Clip.mkv"))
        assertTrue(model.text.contains("40"))
    }

    @Test fun `only transcodes in progress count as Transcoding N of M`() {
        val model = QueueNotification.render(
            QueueState(jobs = listOf(tx("t1", DownloadStatus.RUNNING, 0.4f), tx("t2", DownloadStatus.QUEUED), tx("t3", DownloadStatus.QUEUED))),
        )
        assertEquals("Transcoding 1 of 3", model.title)
    }

    @Test fun `a download and a transcode running together get the neutral title`() {
        val model = QueueNotification.render(
            QueueState(jobs = listOf(dl("a", DownloadStatus.RUNNING, 0.5f, "Cats"), tx("t", DownloadStatus.RUNNING, 0.2f))),
        )
        assertEquals("Working on 1 of 2", model.title)
        assertFalse(model.title.contains("Downloading"))
        assertFalse(model.title.contains("Transcoding"))
        // The rest of the model still follows the first running job.
        assertTrue(model.text.contains("Cats"))
        assertEquals(50, model.progress)
        assertEquals("a", model.actionTargetId)
    }

    @Test fun `a running transcode with a download waiting is a mix too`() {
        // The "of M" counts the waiting download, so naming the work "Transcoding" would be untrue of it.
        val model = QueueNotification.render(
            QueueState(jobs = listOf(tx("t", DownloadStatus.RUNNING, 0.2f), dl("b", DownloadStatus.QUEUED))),
        )
        assertEquals("Working on 1 of 2", model.title)
    }

    @Test fun `finished and paused rows of the other kind do not make it a mix`() {
        val history = listOf(dl("old1", DownloadStatus.COMPLETED), dl("old2", DownloadStatus.FAILED), dl("held", DownloadStatus.PAUSED))
        val running = QueueNotification.render(QueueState(jobs = history + tx("t", DownloadStatus.RUNNING, 0.1f)))
        assertEquals("Transcoding 1 of 1", running.title)
        val other = QueueNotification.render(
            QueueState(jobs = listOf(tx("oldTx", DownloadStatus.COMPLETED), tx("heldTx", DownloadStatus.PAUSED), dl("a", DownloadStatus.RUNNING, 0.1f))),
        )
        assertEquals("Downloading 1 of 1", other.title)
    }

    @Test fun `the posted ongoing notification carries the transcode title`() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        QueueNotification.ensureChannel(ctx)
        val n = QueueNotification.build(ctx, QueueState(jobs = listOf(tx("t", DownloadStatus.RUNNING, 0.5f))))
        assertEquals("Transcoding 1 of 1", n.extras.getString(android.app.Notification.EXTRA_TITLE))
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
