package com.sieve.app.transcode

import com.sieve.app.ui.transcode.SourceInput
import com.sieve.app.ui.transcode.TranscodeViewModel
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.QueueJob
import com.sieve.transcode.args.ArgFinalizer
import com.sieve.transcode.args.BuilderEncoder
import com.sieve.transcode.args.FfmpegArgs
import com.sieve.transcode.args.FinalizeOptions
import com.sieve.transcode.detect.EncoderDetector
import com.sieve.transcode.detect.HardwareEncoderInfo
import com.sieve.transcode.detect.VideoEncoderProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TranscodeViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun hwDetector() = EncoderDetector(
        object : VideoEncoderProbe {
            override fun hardwareVideoEncoders() = listOf(HardwareEncoderInfo("video/avc", "c2.qti.avc", true))
            override fun ffmpegEncoderNames() = setOf("h264_mediacodec", "libx264", "libx265")
        },
    ) { 8 }

    @Test fun detectsHardwareEncoderOnInit() = runTest {
        val vm = TranscodeViewModel(hwDetector(), {}, { _, _ -> "/w/x" }, coreCount = { 8 })
        advanceUntilIdle()
        assertEquals("hw-h264", vm.state.value.activeEncoderId)
        assertTrue(vm.state.value.encoders.any { it.id == "hw-h264" })
    }

    @Test fun startBuildsHardwareTranscodeJob() = runTest {
        val sink = mutableListOf<QueueJob>()
        val vm = TranscodeViewModel(
            detector = hwDetector(),
            enqueue = { sink += it },
            materialize = { _, _ -> "/work/src.mkv" },
            coreCount = { 8 },
            outputDirLabel = { "Download/Sieve" },
            idGen = { "t1" },
        )
        advanceUntilIdle()
        vm.selectPreset("h265-1080")
        vm.setCrf(23)
        vm.addSource(SourceInput("content://x", "clip.mkv", null))
        vm.start()
        advanceUntilIdle()

        assertEquals(1, sink.size)
        val spec = sink.first().spec as JobSpec.Transcode
        assertTrue(spec.usedHardwareEncoder)
        assertEquals("/work/src.mkv", spec.inputPath)

        val expected = ArgFinalizer.finalize(
            FfmpegArgs.build("h265-1080", BuilderEncoder.HARDWARE, durationSec = 0.0),
            FinalizeOptions(requestedThreads = 6, emitThreads = false, crfOverride = 23, normalizeAudio = false),
        )
        assertEquals(expected, spec.presetArgs)
        assertEquals("clip.mp4", sink.first().output.outputTemplate) // h265-1080 ext = mp4
    }
}
