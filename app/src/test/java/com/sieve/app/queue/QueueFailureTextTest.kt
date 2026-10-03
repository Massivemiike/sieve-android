package com.sieve.app.queue

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.sieve.app.ui.queue.QueueScreen
import com.sieve.app.ui.queue.QueueUiState
import com.sieve.app.ui.theme.SieveTheme
import com.sieve.engine.args.YtdlpArgs
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the Queue shows under a FAILED row. The case that prompted it: v1.0.3 stored only "yt-dlp exited 1" for a Facebook
 * video whose caption is its title (ext4's 255-byte file-name limit), and the owner saw just that after upgrading to 1.0.4.
 */
@RunWith(RobolectricTestRunner::class)
class QueueFailureTextTest {
    @get:Rule val rule = createComposeRule()

    private val oldTemplate = "%(title)s [%(id)s].%(ext)s"

    /** A Facebook post caption, emoji and all: 360 bytes, 80 characters. */
    private val caption = "🎬🔥 ".repeat(40)

    /** The measured Facebook caption: 221 bytes; its final name (244) fits ext4's 255, the part file of one stream (268) does not. */
    private val facebookCaption = "🎬🔥 ".repeat(24) + "Cats!"

    private fun failed(id: String, error: String, title: String, template: String = oldTemplate, site: String = "Unknown") = QueueJob(
        id, JobSpec.Download("https://www.facebook.com/watch/?v=$id", emptyList()), OutputRequest("Downloads/Sieve", template),
        status = DownloadStatus.FAILED, title = title, site = site, error = error,
    )

    private fun showQueueOf(vararg jobs: QueueJob) {
        rule.setContent {
            SieveTheme {
                QueueScreen(QueueUiState.from(QueueState(jobs = jobs.toList())), {}, {}, {}, {})
            }
        }
    }

    @Test fun legacyRowWithACaptionTitleSaysTheTitleMadeTheFileNameTooLong() {
        showQueueOf(failed("fb", "yt-dlp exited 1", caption))
        rule.onNodeWithTag("error_fb").assertTextEquals("The title made the file name too long — Fixed, so Retry will work.")
    }

    @Test fun legacyFacebookRowWithTheMeasuredCaptionSaysTheTitleMayHaveMadeTheFileNameTooLong() {
        showQueueOf(
            failed("fb221", "yt-dlp exited 1", facebookCaption, site = "facebook"),
            failed("yt221", "yt-dlp exited 1", facebookCaption, site = "youtube"),
        )
        rule.onNodeWithTag("error_fb221")
            .assertTextEquals("The title may have made the file name too long — Fixed, so Retry should work.")
        // The same caption on a site whose part-file suffix is not known is not blamed on a guess.
        rule.onNodeWithTag("error_yt221").assertTextEquals("yt-dlp stopped without reporting a reason (exit 1) — Retry may work.")
    }

    @Test fun aTitleThatFitsOrAKilledRunIsNotBlamedOnTheTitle() {
        showQueueOf(
            failed("yt160", "yt-dlp exited 1", "x".repeat(160), site = "youtube"),
            failed("fb137", "yt-dlp exited 137", facebookCaption, site = "facebook"),
        )
        rule.onNodeWithTag("error_yt160").assertTextEquals("yt-dlp stopped without reporting a reason (exit 1) — Retry may work.")
        rule.onNodeWithTag("error_fb137").assertTextEquals("yt-dlp stopped without reporting a reason (exit 137) — Retry may work.")
    }

    @Test fun legacyRowWithAShortTitleSaysYtDlpStoppedWithoutAReason() {
        showQueueOf(failed("short", "yt-dlp exited 1", "Cats"))
        rule.onNodeWithTag("error_short").assertTextEquals("yt-dlp stopped without reporting a reason (exit 1) — Retry may work.")
    }

    @Test fun aRowWithRealErrorLinesShowsThemHumanizedAsBefore() {
        showQueueOf(
            failed("rate", "ERROR: HTTP Error 429: Too Many Requests", caption, template = YtdlpArgs.DEFAULT_TEMPLATE),
            failed("legacyRate", "ERROR: HTTP Error 429: Too Many Requests", caption),
        )
        rule.onNodeWithTag("error_rate").assertTextEquals("Rate-limited by the site — Wait a few minutes and retry.")
        rule.onNodeWithTag("error_legacyRate").assertTextEquals("Rate-limited by the site — Wait a few minutes and retry.")
    }

    @Test fun textThatMerelyContainsExitedIsLeftAlone() {
        showQueueOf(
            failed("code", "yt-dlp exited with code 2", caption),
            failed("early", "yt-dlp exited 1 early", caption),
        )
        rule.onNodeWithTag("error_code").assertTextEquals("yt-dlp exited with code 2")
        rule.onNodeWithTag("error_early").assertTextEquals("yt-dlp exited 1 early")
    }
}
