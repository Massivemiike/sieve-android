package com.sieve.transcode

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sieve.transcode.runner.FfmpegRunner
import com.sieve.transcode.runner.android.AndroidFfmpegProcessFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The S26 first-tap-does-nothing defect, against the platform's own `java.lang.Process` (the JVM unit test can only model it).
 *
 * On Android `Process.destroy()` is SIGTERM and `destroyForcibly()` is `destroy()` again, so the old cancel never sent a
 * SIGKILL; a child that ignores SIGTERM (ffmpeg wedged in a native MediaCodec call) survived it. The child here is a shell that
 * ignores SIGTERM and never reads stdin, which is what that ffmpeg looked like from outside.
 */
@RunWith(AndroidJUnit4::class)
class HungProcessCancelInstrumentedTest {

    private fun spawnWedged() = AndroidFfmpegProcessFactory().start(
        "/system/bin/sh",
        listOf("-c", "trap '' TERM; while true; do sleep 1; done"),
    )

    @Test fun oneCancelEndsAChildThatIgnoresSigterm() = runBlocking {
        val p = spawnWedged()
        try {
            val t0 = System.nanoTime()
            val seenExit = FfmpegRunner(AndroidFfmpegProcessFactory(), "/system/bin/sh").cancel(p, graceMs = 500, termGraceMs = 500)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("cancel did not see the process end (took $ms ms)", seenExit)
            assertTrue("took $ms ms", ms < 5_000)
            assertEquals("128 + SIGKILL", 137, p.awaitExit())
        } finally {
            p.destroyForcibly()
        }
    }

    /** Not an assertion about the fix: records what this Android build's Process does, for the device-check notes. */
    @Test fun whatThePlatformProcessApiDoes() {
        val child = ProcessBuilder("/system/bin/sh", "-c", "trap '' TERM; while true; do sleep 1; done").start()
        try {
            child.destroy()
            child.destroyForcibly()
            val survived = !child.waitFor(1_500, TimeUnit.MILLISECONDS)
            android.util.Log.i("HungProcessCancel", "child that ignores SIGTERM survived destroy()+destroyForcibly(): $survived")
        } finally {
            // The one stop that always works; Process alone cannot send it, so go through the shell.
            Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "kill -9 ${Regex("pid=(\\d+)").find(child.toString())?.groupValues?.get(1)}")).waitFor()
        }
    }
}
