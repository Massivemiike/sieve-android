package com.sieve.queue.service

import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The in-app snackbar says what the desktop toasts and the system notification say for the same job. */
class JobToastTest {
    private fun dl(status: DownloadStatus, title: String = "Cats", error: String? = null) =
        QueueJob("a", JobSpec.Download("u", emptyList()), OutputRequest("d", "o"), status = status, title = title, error = error)

    private fun tx(status: DownloadStatus, title: String = "Clip.mkv", error: String? = null) =
        QueueJob("t", JobSpec.Transcode("/in.mkv", emptyList(), 10.0, false), OutputRequest("d", "o"), status = status, title = title, error = error)

    @Test fun `a finished download says Downloaded and names the item`() {
        assertEquals("Downloaded: Cats", JobToast.text(dl(DownloadStatus.COMPLETED)))
    }

    @Test fun `a finished transcode says Transcoded not Downloaded`() {
        assertEquals("Transcoded: Clip.mkv", JobToast.text(tx(DownloadStatus.COMPLETED)))
    }

    @Test fun `the completed wording is the system notification's title`() {
        for (job in listOf(dl(DownloadStatus.COMPLETED), tx(DownloadStatus.COMPLETED), dl(DownloadStatus.COMPLETED, title = ""))) {
            assertEquals(QueueNotification.renderDone(job)!!.title, JobToast.text(job))
        }
    }

    @Test fun `a blank title falls back to file like the desktop toast`() {
        assertEquals("Downloaded: file", JobToast.text(dl(DownloadStatus.COMPLETED, title = "")))
    }

    @Test fun `a failed download is Download failed with the humanized reason`() {
        assertEquals(
            "Download failed: Rate-limited by the site — Wait a few minutes and retry.",
            JobToast.text(dl(DownloadStatus.FAILED, error = "ERROR: HTTP Error 429: Too Many Requests")),
        )
    }

    @Test fun `a failed transcode names the file and the reason`() {
        assertEquals(
            "Transcode failed: Clip.mkv — Conversion failed!",
            JobToast.text(tx(DownloadStatus.FAILED, error = "Conversion failed!")),
        )
    }

    @Test fun `a failure without error text has no dangling reason`() {
        assertEquals("Download failed", JobToast.text(dl(DownloadStatus.FAILED, error = null)))
        assertEquals("Download failed", JobToast.text(dl(DownloadStatus.FAILED, error = "  ")))
        assertEquals("Transcode failed: Clip.mkv", JobToast.text(tx(DownloadStatus.FAILED, error = null)))
        assertEquals("Transcode failed: file", JobToast.text(tx(DownloadStatus.FAILED, title = "", error = null)))
    }

    @Test fun `only finished outcomes get a toast`() {
        for (s in listOf(DownloadStatus.QUEUED, DownloadStatus.PREPARING, DownloadStatus.RUNNING, DownloadStatus.PAUSED, DownloadStatus.CANCELLED)) {
            assertNull("$s", JobToast.text(dl(s)))
        }
    }
}
