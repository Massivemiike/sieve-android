package com.sieve.transcode

import com.sieve.transcode.args.ArgFinalizer
import com.sieve.transcode.args.BuilderEncoder.HARDWARE
import com.sieve.transcode.args.BuilderEncoder.SOFTWARE
import com.sieve.transcode.args.FfmpegArgs
import com.sieve.transcode.args.FinalizeOptions
import com.sieve.transcode.args.ThreadCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 5: thread caps. Tasks 6–8: the finalize pipeline (subs, CRF, loudnorm, gain, raw). */
class ArgFinalizerTest {

    private fun opts(
        requestedThreads: Int = 8,
        emitThreads: Boolean = true,
        burnSubtitles: Boolean = false,
        subtitleSource: String? = null,
        crfOverride: Int? = null,
        normalizeAudio: Boolean = false,
        audioGainDb: Int = 0,
        rawArgs: String = "",
    ) = FinalizeOptions(requestedThreads, emitThreads, burnSubtitles, subtitleSource, crfOverride, normalizeAudio, audioGainDb, rawArgs)

    // ── Task 5: ThreadCaps ──────────────────────────────────────────
    @Test fun libx265CapsAt16_theSmokeLockedInvariant() {
        val args = FfmpegArgs.build("h265-1080", SOFTWARE)
        assertEquals(16, ThreadCaps.capThreadsForEncoder(args, 17))
        assertEquals(16, ThreadCaps.capThreadsForEncoder(args, 64))
        assertEquals(8, ThreadCaps.capThreadsForEncoder(args, 8))
    }

    @Test fun libx264CapsAt16() {
        val args = FfmpegArgs.build("h264-1080", SOFTWARE)
        assertEquals(16, ThreadCaps.capThreadsForEncoder(args, 32))
        assertEquals(6, ThreadCaps.capThreadsForEncoder(args, 6))
    }

    @Test fun mediacodecGetsZeroThreads() {
        val args = FfmpegArgs.build("h264-1080", HARDWARE)
        assertEquals(0, ThreadCaps.capThreadsForEncoder(args, 16))
    }

    @Test fun uncappedCodecsCapAt16() {
        assertEquals(16, ThreadCaps.capThreadsForEncoder(FfmpegArgs.build("av1-1080", SOFTWARE), 99))
        assertEquals(16, ThreadCaps.capThreadsForEncoder(FfmpegArgs.build("webm-vp9", SOFTWARE), 99))
        assertEquals(16, ThreadCaps.capThreadsForEncoder(FfmpegArgs.build("prores-422", SOFTWARE), 99))
        assertEquals(4, ThreadCaps.capThreadsForEncoder(FfmpegArgs.build("dnxhr-hq", SOFTWARE), 4))
    }

    @Test fun audioGifWebpGetZeroThreads_noCvPresent() {
        assertEquals(0, ThreadCaps.capThreadsForEncoder(FfmpegArgs.build("mp3-320", SOFTWARE), 16))
        assertEquals(0, ThreadCaps.capThreadsForEncoder(FfmpegArgs.build("gif", SOFTWARE), 16))
        assertEquals(0, ThreadCaps.capThreadsForEncoder(FfmpegArgs.build("webp-anim", SOFTWARE), 16))
    }

    // ── Task 6: threads emission gate ───────────────────────────────
    @Test fun threadsEmittedForSoftware_cappedTo16() {
        val out = ArgFinalizer.finalize(FfmpegArgs.build("h265-1080", SOFTWARE), opts(requestedThreads = 24))
        assertEquals(listOf("-threads", "16"), out.takeLast(2))
    }

    @Test fun threadsNotEmittedWhenEmitThreadsFalse() {
        val out = ArgFinalizer.finalize(FfmpegArgs.build("h264-1080", SOFTWARE), opts(emitThreads = false))
        assertFalse("-threads" in out)
    }

    @Test fun threadsNotEmittedForHardwareEvenWhenEmitTrue() {
        val out = ArgFinalizer.finalize(FfmpegArgs.build("h264-1080", HARDWARE), opts(emitThreads = true, requestedThreads = 8))
        assertFalse("-threads" in out) // cap is 0 → nothing pushed
    }

    // ── Task 7: burn subs, CRF override ─────────────────────────────
    @Test fun burnSubs_appendsToExistingVf() {
        val out = ArgFinalizer.finalize(
            FfmpegArgs.build("h264-1080", SOFTWARE),
            opts(emitThreads = false, burnSubtitles = true, subtitleSource = "a:b'c"),
        )
        val vfIdx = out.indexOf("-vf")
        assertEquals("scale=-2:1080,subtitles='a\\:b\\'c'", out[vfIdx + 1])
    }

    @Test fun burnSubs_pushesNewVfWhenAbsent() {
        // audio preset has no -vf
        val out = ArgFinalizer.finalize(
            FfmpegArgs.build("aac-256", SOFTWARE),
            opts(emitThreads = false, burnSubtitles = true, subtitleSource = "x\\y:z"),
        )
        // backslash -> '/', then colon escaped
        assertEquals(listOf("-vf", "subtitles='x/y\\:z'"), out.takeLast(2))
    }

    @Test fun burnSubs_noOpWhenSourceBlank() {
        val base = FfmpegArgs.build("h264-1080", SOFTWARE)
        val out = ArgFinalizer.finalize(base, opts(emitThreads = false, burnSubtitles = true, subtitleSource = null))
        assertEquals(base, out)
    }

    @Test fun burnSubs_refusedOnGifComplexFiltergraph() {
        // gif's -vf is a labeled palettegen/paletteuse graph; appending ,subtitles= would corrupt it.
        val base = FfmpegArgs.build("gif", SOFTWARE)
        val out = ArgFinalizer.finalize(base, opts(emitThreads = false, burnSubtitles = true, subtitleSource = "s.srt"))
        assertEquals("gif -vf must be left untouched", base, out)
    }

    @Test fun burnSubs_allowedOnCommaChainedPadGraph() {
        // ig-vert's -vf is a simple comma chain (scale,pad) — subtitles CAN be appended safely.
        val out = ArgFinalizer.finalize(
            FfmpegArgs.build("ig-vert", SOFTWARE),
            opts(emitThreads = false, burnSubtitles = true, subtitleSource = "s.srt"),
        )
        val vf = out[out.indexOf("-vf") + 1]
        assertTrue("subtitles should be appended to the pad chain", vf.endsWith(",subtitles='s.srt'"))
    }

    @Test fun crfOverride_replacesValueWhenCrfPresent() {
        val out = ArgFinalizer.finalize(FfmpegArgs.build("h264-1080", SOFTWARE), opts(emitThreads = false, crfOverride = 28))
        val idx = out.indexOf("-crf")
        assertEquals("28", out[idx + 1])
    }

    @Test fun crfOverride_noOpForBitratePreset() {
        val base = FfmpegArgs.build("yt-1080", SOFTWARE) // -b:v, no -crf
        val out = ArgFinalizer.finalize(base, opts(emitThreads = false, crfOverride = 28))
        assertFalse("-crf" in out)
        assertEquals(base, out)
    }

    // ── Task 8: loudnorm, gain, raw, and full-order integration ─────
    @Test fun loudnormPushesFreshAf() {
        val out = ArgFinalizer.finalize(FfmpegArgs.build("h264-1080", SOFTWARE), opts(emitThreads = false, normalizeAudio = true))
        assertEquals(listOf("-af", "loudnorm=I=-16:TP=-1.5:LRA=11"), out.takeLast(2))
    }

    @Test fun gainAndLoudnormShareOneAf_gainAfterLoudnorm() {
        val out = ArgFinalizer.finalize(
            FfmpegArgs.build("h264-1080", SOFTWARE),
            opts(emitThreads = false, normalizeAudio = true, audioGainDb = -6),
        )
        val afIdx = out.indexOf("-af")
        assertEquals(1, out.count { it == "-af" }) // exactly one -af
        assertEquals("loudnorm=I=-16:TP=-1.5:LRA=11,volume=-6dB", out[afIdx + 1])
    }

    @Test fun gainAloneClampedAndFormatted() {
        val out = ArgFinalizer.finalize(FfmpegArgs.build("aac-256", SOFTWARE), opts(emitThreads = false, audioGainDb = 30))
        assertEquals(listOf("-af", "volume=24dB"), out.takeLast(2)) // coerced to +24
    }

    @Test fun rawArgsSplitAndAppendedAtEnd() {
        val out = ArgFinalizer.finalize(
            FfmpegArgs.build("h264-1080", SOFTWARE),
            opts(emitThreads = false, rawArgs = "  -metadata  title=Hi -x264-params keyint=48 "),
        )
        assertEquals(listOf("-metadata", "title=Hi", "-x264-params", "keyint=48"), out.takeLast(4))
    }

    @Test fun fullPipelineOrder_threadsSubsCrfLoudnormGainRaw() {
        val out = ArgFinalizer.finalize(
            FfmpegArgs.build("h264-1080", SOFTWARE),
            opts(
                requestedThreads = 20, emitThreads = true,
                burnSubtitles = true, subtitleSource = "s.srt",
                crfOverride = 19,
                normalizeAudio = true, audioGainDb = 3,
                rawArgs = "-tune film",
            ),
        )
        assertEquals(
            listOf(
                "-c:v", "libx264", "-crf", "19", "-preset", "medium",
                "-vf", "scale=-2:1080,subtitles='s.srt'",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart",
                "-threads", "16",
                "-af", "loudnorm=I=-16:TP=-1.5:LRA=11,volume=3dB",
                "-tune", "film",
            ),
            out,
        )
    }
}
