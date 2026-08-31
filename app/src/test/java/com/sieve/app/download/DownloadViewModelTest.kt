package com.sieve.app.download

import com.sieve.app.ui.download.DownloadViewModel
import com.sieve.engine.model.VideoInfo
import com.sieve.engine.repo.AnalyzeOutcome
import com.sieve.engine.repo.EngineEvent
import com.sieve.engine.repo.YtDlpEngine
import com.sieve.engine.update.UpdateChannel
import com.sieve.engine.update.UpdateCheck
import com.sieve.engine.update.UpdateResult
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.QueueJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeEngine(var outcome: AnalyzeOutcome) : YtDlpEngine {
        override suspend fun analyze(url: String, cookiesBrowser: String?): AnalyzeOutcome = outcome
        override fun download(id: String, url: String, args: List<String>): Flow<EngineEvent> = emptyFlow()
        override fun cancel(id: String): Boolean = true
        override suspend fun version(): String? = "2025.01.01"
        override suspend fun checkUpdate(): UpdateCheck = throw NotImplementedError()
        override suspend fun doUpdate(channel: UpdateChannel): UpdateResult = throw NotImplementedError()
    }

    private val info = VideoInfo(
        id = "abc", title = "My Vid", uploader = "Chan", extractor = "youtube",
        thumbnail = "http://t", duration = 100.0,
    )

    private fun vm(outcome: AnalyzeOutcome, sink: MutableList<QueueJob>) =
        DownloadViewModel(FakeEngine(outcome), { sink += it }, idGen = { "fixed-id" })

    @Test fun analyzeSuccessSetsInfo() = runTest {
        val vm = vm(AnalyzeOutcome.Success(info), mutableListOf())
        vm.onUrlChange("https://youtube.com/watch?v=abc")
        vm.analyze()
        advanceUntilIdle()
        assertEquals(info, vm.state.value.analyzed)
        assertNull(vm.state.value.error)
        assertEquals(false, vm.state.value.analyzing)
    }

    @Test fun analyzeFailureIsHumanized() = runTest {
        val vm = vm(AnalyzeOutcome.Failure("ERROR: HTTP Error 429: Too Many Requests"), mutableListOf())
        vm.onUrlChange("https://x/y")
        vm.analyze()
        advanceUntilIdle()
        assertTrue(vm.state.value.error!!.contains("Rate-limited"))
    }

    @Test fun downloadBuildsJobFromPresetAndInfo() = runTest {
        val sink = mutableListOf<QueueJob>()
        val vm = vm(AnalyzeOutcome.Success(info), sink)
        vm.onUrlChange("https://youtube.com/watch?v=abc")
        vm.analyze(); advanceUntilIdle()
        vm.selectPreset("archive")
        vm.download(); advanceUntilIdle()

        assertEquals(1, sink.size)
        val job = sink.first()
        val spec = job.spec as JobSpec.Download
        assertTrue(spec.engineArgs.contains("-f"))
        assertTrue(spec.engineArgs.contains("bestvideo+bestaudio/best"))
        assertTrue(spec.engineArgs.contains("--embed-subs")) // archive extra args threaded
        assertEquals("%(title)s [%(id)s].%(ext)s", job.output.outputTemplate)
        assertEquals("My Vid", job.title)
        assertEquals("http://t", job.thumbnailUrl)
        assertEquals("youtube", job.site)
    }

    @Test fun downloadWorksWithoutPriorAnalyze() = runTest {
        val sink = mutableListOf<QueueJob>()
        val vm = vm(AnalyzeOutcome.Failure("x"), sink)
        vm.onUrlChange("https://x/y")
        vm.selectPreset("best-1080")
        vm.download(); advanceUntilIdle()
        assertEquals(1, sink.size)
        assertTrue((sink.first().spec as JobSpec.Download).engineArgs.contains("-f"))
        assertEquals("", sink.first().title) // no analyzed info
    }
}
