package com.sieve.app.transcode

import com.sieve.app.ui.transcode.SourceInput
import com.sieve.app.ui.transcode.TranscodeMode
import com.sieve.app.ui.transcode.TranscodeViewModel
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.QueueJob
import com.sieve.transcode.args.ArgFinalizer
import com.sieve.transcode.args.BuilderEncoder
import com.sieve.transcode.args.FfmpegArgs
import com.sieve.transcode.args.FinalizeOptions
import com.sieve.transcode.args.MediaCodecSanitizer
import com.sieve.transcode.detect.EncoderDetector
import com.sieve.transcode.detect.HardwareEncoderInfo
import com.sieve.transcode.detect.VideoEncoderProbe
import kotlinx.coroutines.CompletableDeferred
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
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    /** A VM over [sink], with a counter id generator so batches get distinct ids. */
    private fun vm(
        sink: MutableList<QueueJob> = mutableListOf(),
        materialize: suspend (String, String) -> String = { _, _ -> "/work/src.mkv" },
        probeDuration: suspend (String) -> Double? = { null },
    ): TranscodeViewModel {
        var n = 0
        return TranscodeViewModel(
            detector = hwDetector(),
            enqueue = { sink += it },
            materialize = materialize,
            coreCount = { 8 },
            outputDirLabel = { "Download/Sieve" },
            idGen = { "t${++n}" },
            probeDuration = probeDuration,
        )
    }

    private fun crfOf(args: List<String>): String? = args.indexOf("-crf").takeIf { it >= 0 }?.let { args[it + 1] }

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

    // --- CRF: an untouched slider must leave the preset's own CRF alone (Windows crfOverride = null) ---

    @Test fun untouchedSliderKeepsEveryPresetsOwnCrf() = runTest {
        // Presets whose own CRF is not the old slider default of 23 are the ones that used to be rewritten.
        val expectedCrf = mapOf(
            "yt-source" to "18", "h264-source" to "20", "plex-direct" to "20", "android-tablet" to "21",
            "vp9-source" to "31", "av1-source" to "30", "av1-1080" to "30", "av1-720" to "32", "roku-fire" to "24",
            "h265-1080" to "23", "h265-4k" to "24",
        )
        for ((preset, crf) in expectedCrf) {
            val sink = mutableListOf<QueueJob>()
            val vm = vm(sink)
            advanceUntilIdle()
            vm.selectPreset(preset)
            vm.addSource(SourceInput("content://x", "clip.mkv", null))
            vm.start()
            advanceUntilIdle()

            val spec = sink.single().spec as JobSpec.Transcode
            assertEquals(crf, crfOf(spec.presetArgs), "preset $preset")
            val untouched = ArgFinalizer.finalize(
                FfmpegArgs.build(preset, BuilderEncoder.HARDWARE),
                FinalizeOptions(requestedThreads = 6, emitThreads = false, crfOverride = null),
            )
            assertEquals(untouched, spec.presetArgs, "preset $preset")
        }
    }

    @Test fun sliderShowsThePresetsOwnCrfUntilMoved() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.selectPreset("yt-source")
        assertEquals(18, vm.state.value.shownCrf)
        assertEquals(18, vm.state.value.presetCrf)
        assertNull(vm.state.value.crfOverride)

        vm.selectPreset("av1-source")
        assertEquals(30, vm.state.value.shownCrf) // follows the preset, not a global default
        assertEquals(63, vm.state.value.crfMax)   // libsvtav1's scale
        vm.selectPreset("h264-1080")
        assertEquals(51, vm.state.value.crfMax)
    }

    @Test fun explicitOverrideIsApplied() = runTest {
        val sink = mutableListOf<QueueJob>()
        val vm = vm(sink)
        advanceUntilIdle()
        vm.selectPreset("yt-source")
        vm.setCrf(28)
        assertEquals(28, vm.state.value.shownCrf)
        vm.addSource(SourceInput("content://x", "clip.mkv", null))
        vm.start()
        advanceUntilIdle()

        assertEquals("28", crfOf((sink.single().spec as JobSpec.Transcode).presetArgs))
    }

    @Test fun draggingBackToThePresetValueClearsTheOverride() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.selectPreset("yt-source")
        vm.setCrf(28)
        assertEquals(28, vm.state.value.crfOverride)
        vm.setCrf(18) // the preset's own
        assertNull(vm.state.value.crfOverride)
        assertEquals(18, vm.state.value.shownCrf)
    }

    @Test fun overrideIsClampedToTheScaleOfTheSelectedPreset() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.selectPreset("h264-1080")
        vm.setCrf(99)
        assertEquals(51, vm.state.value.shownCrf)
        vm.setCrf(0)
        assertEquals(1, vm.state.value.shownCrf)
        vm.selectPreset("av1-1080")
        vm.setCrf(99)
        assertEquals(63, vm.state.value.shownCrf)
    }

    @Test fun presetsWithoutACrfHaveNoSliderAndIgnoreTheOverride() = runTest {
        val sink = mutableListOf<QueueJob>()
        val vm = vm(sink)
        advanceUntilIdle()
        for (preset in listOf("yt-1080", "discord-25", "mp3-320", "prores-hq", "gif")) { // bitrate / audio / intermediate / image
            vm.selectPreset(preset)
            assertNull(vm.state.value.shownCrf, preset)
            vm.setCrf(28) // a stale call must not stick
            assertNull(vm.state.value.crfOverride, preset)
        }
        vm.selectPreset("yt-1080")
        vm.addSource(SourceInput("content://x", "clip.mkv", null))
        vm.start()
        advanceUntilIdle()
        assertNull(crfOf((sink.single().spec as JobSpec.Transcode).presetArgs))
    }

    // The MediaCodec path derives -b:v from the CRF in the persisted args, so an overwritten CRF skewed it too.
    @Test fun mediaCodecBitrateIsDerivedFromThePresetsOwnCrf() = runTest {
        val sink = mutableListOf<QueueJob>()
        val vm = vm(sink)
        advanceUntilIdle()
        assertEquals("hw-h264", vm.state.value.activeEncoderId)
        vm.selectPreset("yt-source") // CRF 18
        vm.addSource(SourceInput("content://x", "clip.mkv", null))
        vm.start()
        advanceUntilIdle()

        val spawned = MediaCodecSanitizer.sanitize((sink.single().spec as JobSpec.Transcode).presetArgs, 1080)
        val bv = spawned[spawned.indexOf("-b:v") + 1]
        assertEquals("${MediaCodecSanitizer.targetKbps("h264_mediacodec", 1080, 18)}k", bv)
        assertTrue(MediaCodecSanitizer.targetKbps("h264_mediacodec", 1080, 18) > MediaCodecSanitizer.targetKbps("h264_mediacodec", 1080, 23))
    }

    // --- Start: re-entrancy and what survives a copy -------------------------------------------------

    @Test fun aSecondStartWhileCopyingDoesNotEnqueueAgain() = runTest {
        val sink = mutableListOf<QueueJob>()
        val gate = CompletableDeferred<Unit>()
        var copies = 0
        val vm = vm(sink, materialize = { _, _ -> copies++; gate.await(); "/work/src.mkv" })
        advanceUntilIdle()
        vm.addSource(SourceInput("content://x", "clip.mkv", null))

        vm.start()
        vm.start() // same tick, before the first coroutine has even run
        advanceUntilIdle()
        vm.start() // and again mid-copy
        assertTrue(vm.state.value.starting)
        assertFalse(vm.state.value.canStart)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, copies)
        assertEquals(1, sink.size)
        assertFalse(vm.state.value.starting)
        assertTrue(vm.state.value.sources.isEmpty())
    }

    @Test fun aSourceAddedWhileCopyingSurvivesStart() = runTest {
        val sink = mutableListOf<QueueJob>()
        val gate = CompletableDeferred<Unit>()
        val vm = vm(sink, materialize = { uri, _ -> if (uri == "content://a") gate.await(); "/work/${uri.takeLast(1)}.mkv" })
        advanceUntilIdle()
        vm.addSource(SourceInput("content://a", "a.mkv", null))
        vm.start()
        advanceUntilIdle()
        vm.addSource(SourceInput("content://b", "b.mkv", null)) // picked while a is still copying

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("a.mp4"), sink.map { it.title })                                 // only a was queued
        assertEquals(listOf("content://b"), vm.state.value.sources.map { it.uri })           // b is still there
        assertTrue(vm.state.value.canStart)
    }

    @Test fun batchQueuesEverySourceAndClearsTheList() = runTest {
        val sink = mutableListOf<QueueJob>()
        val vm = vm(sink, materialize = { uri, _ -> "/work/${uri.takeLast(1)}.mkv" })
        advanceUntilIdle()
        vm.setMode(TranscodeMode.BATCH)
        vm.addSource(SourceInput("content://a", "a.mkv", null))
        vm.addSource(SourceInput("content://b", "b.mkv", null))
        vm.start()
        advanceUntilIdle()

        assertEquals(listOf("/work/a.mkv", "/work/b.mkv"), sink.map { (it.spec as JobSpec.Transcode).inputPath })
        assertTrue(vm.state.value.sources.isEmpty())
        assertFalse(vm.state.value.starting)
    }

    @Test fun aSourceThatCannotBeReadStaysListedAndTheOthersStillGo() = runTest {
        val sink = mutableListOf<QueueJob>()
        val vm = vm(sink, materialize = { uri, _ -> if (uri == "content://bad") throw IOException("EACCES") else "/work/ok.mkv" })
        advanceUntilIdle()
        vm.setMode(TranscodeMode.BATCH)
        vm.addSource(SourceInput("content://bad", "bad.mkv", null))
        vm.addSource(SourceInput("content://ok", "ok.mkv", null))
        vm.start()
        advanceUntilIdle() // must not crash the VM's scope

        assertEquals(1, sink.size)
        assertEquals(listOf("content://bad"), vm.state.value.sources.map { it.uri })
        assertTrue(vm.state.value.startError!!.contains("bad.mkv"))
        assertFalse(vm.state.value.starting) // Start is usable again
        assertTrue(vm.state.value.canStart)
    }

    @Test fun aSourceRemovedMeanwhileIsNotQueued() = runTest {
        val sink = mutableListOf<QueueJob>()
        val gate = CompletableDeferred<Unit>()
        val vm = vm(sink, materialize = { uri, _ -> if (uri == "content://a") gate.await(); "/work/${uri.takeLast(1)}.mkv" })
        advanceUntilIdle()
        vm.setMode(TranscodeMode.BATCH)
        vm.addSource(SourceInput("content://a", "a.mkv", null))
        vm.addSource(SourceInput("content://b", "b.mkv", null))
        vm.start()
        advanceUntilIdle()
        vm.removeSource("content://b") // while a is copying

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("/work/a.mkv"), sink.map { (it.spec as JobSpec.Transcode).inputPath })
    }

    // --- real source duration -> determinate progress ---------------------------------------------

    @Test fun probedDurationFeedsTheJobAndTheRow() = runTest {
        val sink = mutableListOf<QueueJob>()
        val probed = mutableListOf<String>()
        val vm = vm(sink, probeDuration = { probed += it; 125.5 })
        advanceUntilIdle()
        vm.addSource(SourceInput("content://x", "clip.mkv", null))
        vm.start()
        advanceUntilIdle()

        assertEquals(listOf("/work/src.mkv"), probed) // probed on the materialized copy
        assertEquals(125.5, (sink.single().spec as JobSpec.Transcode).totalDurationSec)
        assertEquals(125L, sink.single().durationSec)
    }

    @Test fun aKnownSourceDurationIsNotReprobed() = runTest {
        val sink = mutableListOf<QueueJob>()
        var probes = 0
        val vm = vm(sink, probeDuration = { probes++; 999.0 })
        advanceUntilIdle()
        vm.addSource(SourceInput("content://x", "clip.mkv", 60.0))
        vm.start()
        advanceUntilIdle()

        assertEquals(0, probes)
        assertEquals(60.0, (sink.single().spec as JobSpec.Transcode).totalDurationSec)
    }

    @Test fun anUnreadableDurationStaysNullSoProgressIsIndeterminate() = runTest {
        val sink = mutableListOf<QueueJob>()
        val vm = vm(sink, probeDuration = { null })
        advanceUntilIdle()
        vm.addSource(SourceInput("content://x", "clip.mkv", null))
        vm.start()
        advanceUntilIdle()

        assertNull((sink.single().spec as JobSpec.Transcode).totalDurationSec)
        assertNull(sink.single().durationSec)
    }
}
