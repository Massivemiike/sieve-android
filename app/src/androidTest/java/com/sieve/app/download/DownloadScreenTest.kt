package com.sieve.app.download

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.sieve.app.ui.download.DownloadRoute
import com.sieve.app.ui.download.DownloadViewModel
import com.sieve.app.ui.theme.SieveTheme
import com.sieve.engine.model.VideoInfo
import com.sieve.engine.repo.AnalyzeOutcome
import com.sieve.engine.repo.EngineEvent
import com.sieve.engine.repo.YtDlpEngine
import com.sieve.engine.update.UpdateChannel
import com.sieve.engine.update.UpdateCheck
import com.sieve.engine.update.UpdateResult
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.QueueJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeEngine(val info: VideoInfo) : YtDlpEngine {
        override suspend fun analyze(url: String, cookiesBrowser: String?) = AnalyzeOutcome.Success(info)
        override fun download(id: String, url: String, args: List<String>): Flow<EngineEvent> = emptyFlow()
        override fun cancel(id: String): Boolean = true
        override suspend fun version(): String? = "2025.01.01"
        override suspend fun checkUpdate(): UpdateCheck = throw NotImplementedError()
        override suspend fun doUpdate(channel: UpdateChannel): UpdateResult = throw NotImplementedError()
    }

    @Test
    fun analyzeShowsInfoThenDownloadEnqueues() {
        val sink = mutableListOf<QueueJob>()
        val info = VideoInfo(id = "x", title = "Test Clip Title", extractor = "youtube", thumbnail = "")
        val vm = DownloadViewModel(FakeEngine(info), { sink += it }, idGen = { "fixed" })

        rule.setContent { SieveTheme { DownloadRoute(vm) } }

        rule.onNodeWithTag("url_field").performTextInput("https://youtube.com/watch?v=x")
        rule.onNodeWithTag("analyze_btn").performClick()
        rule.waitUntil(4_000) {
            rule.onAllNodesWithText("Test Clip Title").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Test Clip Title").assertIsDisplayed()

        rule.onNodeWithTag("preset_best-720").performClick()
        rule.onNodeWithTag("download_btn").performClick()
        rule.waitUntil(4_000) { sink.isNotEmpty() }

        assertEquals(1, sink.size)
        assertTrue((sink.first().spec as JobSpec.Download).engineArgs.contains("-f"))
    }
}
