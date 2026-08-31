package com.sieve.app.transcode

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.sieve.app.ui.theme.SieveTheme
import com.sieve.app.ui.transcode.SourceInput
import com.sieve.app.ui.transcode.TranscodeRoute
import com.sieve.app.ui.transcode.TranscodeViewModel
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.QueueJob
import com.sieve.transcode.detect.EncoderDetector
import com.sieve.transcode.detect.HardwareEncoderInfo
import com.sieve.transcode.detect.VideoEncoderProbe
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscodeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun hwDetector() = EncoderDetector(
        object : VideoEncoderProbe {
            override fun hardwareVideoEncoders() = listOf(HardwareEncoderInfo("video/avc", "c2.qti.avc", true))
            override fun ffmpegEncoderNames() = setOf("h264_mediacodec", "libx264", "libx265")
        },
    ) { 8 }

    @Test
    fun showsHardwareEncoderAndStartEnqueues() {
        val sink = mutableListOf<QueueJob>()
        val vm = TranscodeViewModel(hwDetector(), { sink += it }, { _, _ -> "/work/src.mkv" }, coreCount = { 8 }, idGen = { "t" })
        vm.addSource(SourceInput("content://x", "clip.mkv", null))

        rule.setContent { SieveTheme { TranscodeRoute(vm) } }

        rule.onNodeWithTag("encoder_hw-h264").assertExists()
        rule.onNodeWithTag("transcode_list").performScrollToNode(hasTestTag("start_btn"))
        rule.onNodeWithTag("start_btn").performClick()
        rule.waitUntil(4_000) { sink.isNotEmpty() }

        assertEquals(1, sink.size)
        assertTrue((sink.first().spec as JobSpec.Transcode).usedHardwareEncoder)
    }
}
