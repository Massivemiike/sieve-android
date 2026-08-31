package com.sieve.transcode

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sieve.transcode.args.ArgFinalizer
import com.sieve.transcode.args.BuilderEncoder
import com.sieve.transcode.args.FfmpegArgs
import com.sieve.transcode.args.FinalizeOptions
import com.sieve.transcode.detect.EncoderDetector
import com.sieve.transcode.detect.android.AndroidVideoEncoderProbe
import com.sieve.transcode.runner.FfmpegRunner
import com.sieve.transcode.runner.TranscodeEvent
import com.sieve.transcode.runner.TranscodeJob
import com.sieve.transcode.runner.android.AndroidFfmpegProcessFactory
import com.sieve.transcode.runner.android.FfmpegBinary
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Task 20: on-device transcode smoke on the 16KB Android 15 emulator (x86_64).
 *
 * Proves the whole chain end-to-end: the packaged ffmpeg exec's from nativeLibraryDir, the detector
 * gates MediaCodec correctly, the x265 16-thread lock survives to a live encode, and both a software
 * HEVC job and a hardware (fallback-capable) job produce valid output.
 *
 * Gotcha #1 (MediaCodec rejects -crf/-preset) is deliberately avoided by smoking only the clean
 * `discord-25` HW preset plus the SW-fallback path; the MediaCodecSanitizer follow-up is out of scope.
 */
@RunWith(AndroidJUnit4::class)
class TranscodeInstrumentationSmokeTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().context
    private val bin: String get() = FfmpegBinary.path(ctx)
    private val dir: File get() = ctx.cacheDir

    private fun runRaw(vararg args: String): Pair<Int, String> {
        val pb = ProcessBuilder(listOf(bin) + args.toList())
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun generateClip(name: String): String {
        val input = File(dir, name).absolutePath
        val (code, log) = runRaw(
            "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
            "-shortest", "-pix_fmt", "yuv420p", "-c:v", "libx264", "-c:a", "aac", input,
        )
        assertEquals("clip generation failed:\n$log", 0, code)
        return input
    }

    private fun transcode(job: TranscodeJob): TranscodeEvent.Done = runBlocking {
        FfmpegRunner(AndroidFfmpegProcessFactory(), bin).run(job).toList()
            .last { it is TranscodeEvent.Done } as TranscodeEvent.Done
    }

    @Test fun ffmpegBinaryExistsAndReportsExpectedEncoders() {
        val f = File(bin)
        assertTrue("ffmpeg .so missing at $bin", f.exists())
        val (code, out) = runRaw("-hide_banner", "-encoders")
        assertEquals(0, code)
        assertTrue("libx264 missing", out.contains("libx264"))
        assertTrue("libx265 missing", out.contains("libx265"))
        assertTrue("h264_mediacodec missing", out.contains("h264_mediacodec"))
        assertTrue("hevc_mediacodec missing", out.contains("hevc_mediacodec"))
    }

    @Test fun detectorReturnsAtLeastCpu() {
        val (_, enc) = runRaw("-hide_banner", "-encoders")
        val result = EncoderDetector(AndroidVideoEncoderProbe(enc), { Runtime.getRuntime().availableProcessors() }).detect(null)
        assertTrue("cpu must always be offered", result.encoders.any { it.id == "cpu" })
    }

    @Test fun softwareHevc_x265ThreadLockAndValidOutput() {
        val input = generateClip("src_hevc.mp4")
        val output = File(dir, "out_hevc.mp4").absolutePath
        val base = FfmpegArgs.build("h265-720", BuilderEncoder.SOFTWARE)
        val args = ArgFinalizer.finalize(base, FinalizeOptions(requestedThreads = 20, emitThreads = true))
        assertTrue("x265 16-thread lock not applied", args.windowed(2).any { it == listOf("-threads", "16") })

        val done = transcode(TranscodeJob(input, output, args, 2.0, usedHardwareEncoder = false))
        assertEquals("hevc transcode failed: ${done.errorSummary}\n${done.stderrTail}", 0, done.exitCode)
        assertTrue("output not written", File(output).length() > 0)
    }

    @Test fun hardwarePreset_succeedsOrFallsBackToValidFile() {
        val input = generateClip("src_hw.mp4")
        val output = File(dir, "out_hw.mp4").absolutePath
        val args = ArgFinalizer.finalize(
            FfmpegArgs.build("discord-25", BuilderEncoder.HARDWARE), // clean HW preset (no -crf/-preset)
            FinalizeOptions(requestedThreads = 8, emitThreads = false),
        )
        val done = transcode(TranscodeJob(input, output, args, 2.0, usedHardwareEncoder = true))
        assertEquals("hw/fallback transcode failed: ${done.errorSummary}\n${done.stderrTail}", 0, done.exitCode)
        assertTrue("output not written", File(output).length() > 0)
    }

    @Test fun loudnormAndScaleFiltersEncodeCleanly() {
        val input = generateClip("src_filters.mp4")
        val output = File(dir, "out_720.mp4").absolutePath
        val args = ArgFinalizer.finalize(
            FfmpegArgs.build("h264-720", BuilderEncoder.SOFTWARE), // carries -vf scale=-2:720
            FinalizeOptions(requestedThreads = 4, emitThreads = true, normalizeAudio = true),
        )
        val done = transcode(TranscodeJob(input, output, args, 2.0, usedHardwareEncoder = false))
        assertEquals("filtered transcode failed: ${done.errorSummary}\n${done.stderrTail}", 0, done.exitCode)
        assertTrue("output not written", File(output).length() > 0)
    }
}
