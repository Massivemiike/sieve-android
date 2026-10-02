package com.sieve.queue.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DoneNotificationTest {
    private val app: android.app.Application get() = RuntimeEnvironment.getApplication()
    private val saved = "content://media/external/downloads/7"

    private fun dl(status: DownloadStatus, title: String = "Cats", filePath: String? = null, error: String? = null) =
        QueueJob("a", JobSpec.Download("u", emptyList()), OutputRequest("d", "o"), status = status, title = title, filePath = filePath, error = error)

    private fun tx(status: DownloadStatus, title: String = "Clip.mkv", filePath: String? = null) =
        QueueJob("t", JobSpec.Transcode("/in.mkv", emptyList(), 10.0, false), OutputRequest("d", "o"), status = status, title = title, filePath = filePath)

    private fun posted(): List<Notification> =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    private fun title(n: Notification) = n.extras.getString(Notification.EXTRA_TITLE)
    private fun text(n: Notification) = n.extras.getString(Notification.EXTRA_TEXT)

    /** Makes getLaunchIntentForPackage resolve to a launcher activity, as the real app's manifest does. */
    private fun registerLauncher() {
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        val ri = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = app.packageName; name = "com.sieve.app.MainActivity" }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(probe, ri)
    }

    // ---- what the notification says (pure) ----

    @Test fun `a finished download says Downloaded and offers to open the file`() {
        val m = QueueNotification.renderDone(dl(DownloadStatus.COMPLETED, filePath = saved))!!
        assertEquals("Downloaded: Cats", m.title)
        assertEquals("Tap to open", m.text)
        assertEquals(saved, m.openUri)
        assertFalse(m.failed)
    }

    @Test fun `a finished transcode says Transcoded`() {
        assertEquals("Transcoded: Clip.mkv", QueueNotification.renderDone(tx(DownloadStatus.COMPLETED, filePath = saved))!!.title)
    }

    @Test fun `without a Uri the completed notification has nothing to open`() {
        val m = QueueNotification.renderDone(dl(DownloadStatus.COMPLETED, filePath = "Download/Sieve/Cats.mp4"))!!
        assertNull(m.openUri)
        assertNull(m.text)
    }

    @Test fun `a blank title falls back to file like the desktop toast`() {
        assertEquals("Downloaded: file", QueueNotification.renderDone(dl(DownloadStatus.COMPLETED, title = ""))!!.title)
    }

    @Test fun `a failure says Failed with the humanized error and opens the queue`() {
        val m = QueueNotification.renderDone(dl(DownloadStatus.FAILED, error = "ERROR: HTTP Error 429: Too Many Requests"))!!
        assertEquals("Failed: Cats", m.title)
        assertEquals("Rate-limited by the site — Wait a few minutes and retry.", m.text)
        assertTrue(m.failed)
        assertNull(m.openUri)
    }

    @Test fun `a failure with no error text still has a sensible message`() {
        assertEquals("Download failed", QueueNotification.renderDone(dl(DownloadStatus.FAILED, error = null))!!.text)
    }

    @Test fun `only finished outcomes are announced`() {
        for (s in listOf(DownloadStatus.QUEUED, DownloadStatus.PREPARING, DownloadStatus.RUNNING, DownloadStatus.PAUSED, DownloadStatus.CANCELLED)) {
            assertNull("$s", QueueNotification.renderDone(dl(s)))
        }
    }

    @Test fun `each job gets its own notification id and never the foreground one`() {
        assertNotEquals(QueueNotification.doneId("a"), QueueNotification.doneId("b"))
        assertEquals(QueueNotification.doneId("a"), QueueNotification.doneId("a")) // a retry replaces, not stacks
        assertNotEquals(QueueNotification.FGID, QueueNotification.doneId("a"))
    }

    // ---- posting ----

    @Test fun `posts on the Completed channel and a tap opens the finished file`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(QueueNotification.postDone(app, dl(DownloadStatus.COMPLETED, filePath = saved)))

        val n = posted().single()
        assertEquals(QueueNotification.DONE_CHANNEL_ID, n.channelId)
        assertEquals("Downloaded: Cats", title(n))
        assertEquals("Tap to open", text(n))
        assertTrue((n.flags and Notification.FLAG_AUTO_CANCEL) != 0)
        val tap = shadowOf(n.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, tap.action)
        assertEquals(saved, tap.data.toString())
        assertTrue(tap.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun `a completed job with no Uri opens the app instead`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        registerLauncher()
        assertTrue(QueueNotification.postDone(app, dl(DownloadStatus.COMPLETED, filePath = "Download/Sieve/Cats.mp4")))

        val tap = shadowOf(posted().single().contentIntent).savedIntent
        assertEquals("com.sieve.app.MainActivity", tap.component!!.className)
        assertFalse(tap.getBooleanExtra(QueueNotification.EXTRA_OPEN_QUEUE, false))
    }

    @Test fun `a failure posts the error and a tap opens the queue`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        registerLauncher()
        assertTrue(QueueNotification.postDone(app, dl(DownloadStatus.FAILED, error = "ERROR: HTTP Error 429: Too Many Requests")))

        val n = posted().single()
        assertEquals("Failed: Cats", title(n))
        assertEquals("Rate-limited by the site — Wait a few minutes and retry.", text(n))
        val tap = shadowOf(n.contentIntent).savedIntent
        assertEquals("com.sieve.app.MainActivity", tap.component!!.className)
        assertTrue(tap.getBooleanExtra(QueueNotification.EXTRA_OPEN_QUEUE, false))
        // Explicit component + CLEAR_TOP/SINGLE_TOP so a running MainActivity gets the extra via onNewIntent.
        assertTrue(tap.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(tap.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test fun `without a launcher activity it still posts, just with no tap target`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(QueueNotification.postDone(app, dl(DownloadStatus.FAILED, error = "boom")))
        assertNull(posted().single().contentIntent)
    }

    @Test fun `a missing notification permission posts nothing and does not crash`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(QueueNotification.postDone(app, dl(DownloadStatus.COMPLETED, filePath = saved)))
        assertFalse(QueueNotification.postDone(app, dl(DownloadStatus.FAILED, error = "boom")))
        assertTrue(posted().isEmpty())
    }

    @Test fun `notifications switched off in settings post nothing and do not crash`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertFalse(QueueNotification.postDone(app, dl(DownloadStatus.COMPLETED, filePath = saved)))
        assertTrue(posted().isEmpty())
    }

    @Test fun `a job that is not finished posts nothing`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(QueueNotification.postDone(app, dl(DownloadStatus.CANCELLED)))
        assertTrue(posted().isEmpty())
    }

    @Test @Config(sdk = [30]) fun `before Android 13 no runtime permission is needed`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(QueueNotification.postDone(app, dl(DownloadStatus.COMPLETED, filePath = saved)))
        assertNotNull(posted().singleOrNull())
    }
}
