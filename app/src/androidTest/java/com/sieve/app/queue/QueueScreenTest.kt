package com.sieve.app.queue

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sieve.app.ui.queue.QueueRoute
import com.sieve.app.ui.queue.QueueViewModel
import com.sieve.app.ui.theme.SieveTheme
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import com.sieve.queue.core.UnifiedProgress
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class QueueScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun rendersRunningJobAndPauseFires() {
        val paused = mutableListOf<String>()
        val job = QueueJob(
            id = "j1",
            spec = JobSpec.Download("https://x/j1", emptyList()),
            output = OutputRequest("Download/Sieve", "%(title)s.%(ext)s"),
            status = DownloadStatus.RUNNING,
            progress = UnifiedProgress(fraction = 0.63f, speed = "4.2MiB/s", eta = "00:41"),
            title = "Blender Open Movie",
        )
        val vm = QueueViewModel(MutableStateFlow(QueueState(jobs = listOf(job))), onPause = { paused += it })

        rule.setContent { SieveTheme { QueueRoute(vm) } }

        rule.onNodeWithText("Blender Open Movie").assertIsDisplayed()
        rule.onNodeWithText("63% · 4.2MiB/s · 00:41 left").assertIsDisplayed()
        rule.onNodeWithTag("pause_j1").performClick()
        assertEquals(listOf("j1"), paused)
    }
}
