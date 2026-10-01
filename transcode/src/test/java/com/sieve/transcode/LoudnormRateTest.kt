package com.sieve.transcode

import com.sieve.transcode.args.ArgFinalizer
import com.sieve.transcode.args.BuilderEncoder.SOFTWARE
import com.sieve.transcode.args.FfmpegArgs
import com.sieve.transcode.args.FinalizeOptions
import com.sieve.transcode.args.LoudnormRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LoudnormRateTest {

    private val loudnorm = "loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000"
    private val args = listOf("-vn", "-c:a", "flac", "-frame_size", "4608", "-af", loudnorm)

    @Test
    fun `source rate 44100 replaces aresample 48000`() {
        val out = LoudnormRate.restore(args, 44100)
        assertEquals(
            listOf("-vn", "-c:a", "flac", "-frame_size", "4608", "-af", "loudnorm=I=-16:TP=-1.5:LRA=11,aresample=44100"),
            out,
        )
    }

    @Test
    fun `source rate 48000 is a no-op`() {
        assertEquals(args, LoudnormRate.restore(args, 48000))
    }

    @Test
    fun `null sample rate is a no-op`() {
        assertEquals(args, LoudnormRate.restore(args, null))
    }

    @Test
    fun `out-of-range sample rates are a no-op`() {
        assertEquals(args, LoudnormRate.restore(args, 7999))
        assertEquals(args, LoudnormRate.restore(args, 192001))
        assertEquals(args, LoudnormRate.restore(args, 0))
        assertEquals(args, LoudnormRate.restore(args, -44100))
    }

    @Test
    fun `range bounds 8000 and 192000 are inclusive`() {
        assertEquals("loudnorm=I=-16:TP=-1.5:LRA=11,aresample=8000", LoudnormRate.restore(args, 8000).last())
        assertEquals("loudnorm=I=-16:TP=-1.5:LRA=11,aresample=192000", LoudnormRate.restore(args, 192000).last())
    }

    @Test
    fun `no loudnorm means no change even if aresample 48000 is present`() {
        val plain = listOf("-vn", "-c:a", "aac", "-af", "aresample=48000")
        assertEquals(plain, LoudnormRate.restore(plain, 44100))
    }

    @Test
    fun `loudnorm without the aresample 48000 token is untouched`() {
        val legacy = listOf("-af", "loudnorm=I=-16:TP=-1.5:LRA=11")
        assertEquals(legacy, LoudnormRate.restore(legacy, 44100))
    }

    @Test
    fun `args without an af flag are untouched`() {
        val none = listOf("-c:v", "libx264", "-crf", "23")
        assertEquals(none, LoudnormRate.restore(none, 44100))
    }

    @Test
    fun `trailing af flag with no value is untouched`() {
        val dangling = listOf("-c:a", "aac", "-af")
        assertEquals(dangling, LoudnormRate.restore(dangling, 44100))
    }

    @Test
    fun `gain chain after aresample is preserved`() {
        val withGain = listOf("-af", "$loudnorm,volume=3dB")
        assertEquals(
            listOf("-af", "loudnorm=I=-16:TP=-1.5:LRA=11,aresample=44100,volume=3dB"),
            LoudnormRate.restore(withGain, 44100),
        )
    }

    @Test
    fun `an earlier filter ahead of loudnorm is preserved`() {
        val chained = listOf("-af", "highpass=f=80,$loudnorm,volume=-6dB")
        assertEquals(
            listOf("-af", "highpass=f=80,loudnorm=I=-16:TP=-1.5:LRA=11,aresample=22050,volume=-6dB"),
            LoudnormRate.restore(chained, 22050),
        )
    }

    @Test
    fun `input list is not mutated and a no-op returns the same instance`() {
        val before = args.toList()
        LoudnormRate.restore(args, 44100)
        assertEquals(before, args)
        assertSame(args, LoudnormRate.restore(args, null))
    }

    @Test
    fun `restores the rate end to end on ArgFinalizer output`() {
        val finalized = ArgFinalizer.finalize(
            FfmpegArgs.build("flac", SOFTWARE),
            FinalizeOptions(requestedThreads = 8, emitThreads = false, normalizeAudio = true, audioGainDb = 3),
        )
        assertEquals(
            listOf("-vn", "-c:a", "flac", "-frame_size", "4608", "-af",
                "loudnorm=I=-16:TP=-1.5:LRA=11,aresample=44100,volume=3dB"),
            LoudnormRate.restore(finalized, 44100),
        )
    }
}
