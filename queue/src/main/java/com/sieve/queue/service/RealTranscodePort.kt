package com.sieve.queue.service

import com.sieve.transcode.runner.FfmpegProcess
import com.sieve.transcode.runner.FfmpegProcessFactory
import com.sieve.transcode.runner.FfmpegRunner
import com.sieve.transcode.runner.TranscodeEvent
import com.sieve.transcode.runner.TranscodeJob
import com.sieve.transcode.runner.android.AndroidFfmpegProcessFactory
import com.sieve.transcode.runner.android.SourceProbe
import com.sieve.transcode.runner.android.SourceVideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Production [TranscodePort] over [FfmpegRunner]. Each [run] wraps the real process factory so the
 * [FfmpegProcess] it spawns is recorded under THAT run's job id (the id is captured per call — several
 * transcodes can be in flight, see Settings > Max transcodes), so [cancel] reaches the right process.
 *
 * Reconciled to the real transcode API (the plan sketched a `(cmd) -> java.lang.Process` factory;
 * the module's real seam is `FfmpegProcessFactory` returning a `FfmpegProcess`).
 */
class RealTranscodePort internal constructor(
    private val binaryPath: String,
    private val delegate: FfmpegProcessFactory,
    /** The runner's bounds (stall watchdog, first-progress bound, grace periods). Production always uses the defaults; tests that run a fake process under a virtual clock widen the stall bounds, see below. */
    private val limits: FfmpegRunner.Limits,
    private val probe: (String) -> SourceVideoInfo?,
) : TranscodePort {

    internal constructor(binaryPath: String, delegate: FfmpegProcessFactory, probe: (String) -> SourceVideoInfo?) :
        this(binaryPath, delegate, FfmpegRunner.Limits(), probe)

    constructor(binaryPath: String) : this(binaryPath, AndroidFfmpegProcessFactory(), SourceProbe::probe)

    /** What [cancel] needs to know about one in-flight [run]. */
    private class ActiveRun {
        /** The most recently spawned process (a HW->SW retry replaces the first). */
        @Volatile var process: FfmpegProcess? = null

        /** Set by [cancel]; honoured at the moments a cancel has no process to act on yet. */
        @Volatile var cancelRequested = false
    }

    private val active = ConcurrentHashMap<String, ActiveRun>()

    // cancel() never spawns, so it does not depend on which factory this runner was built with.
    private val canceller = FfmpegRunner(delegate, binaryPath)

    override fun run(id: String, job: TranscodeJob): Flow<TranscodeEvent> = flow {
        val run = ActiveRun()
        active[id] = run
        try {
            // Spawn-time source adaptation (persisted preset args stay byte-exact):
            //  - AV1 inputs must hardware-decode (`-c:v av1_mediacodec` before -i) — the bundled ffmpeg
            //    has no working software AV1 decoder, so they otherwise fail with 0 frames encoded.
            //  - MediaCodec encoders ignore -crf/-preset and default to ~200 kbps: the sanitizer strips
            //    them and injects a short-side/CRF-derived -b:v.
            //  - "Normalize audio" appends `aresample=48000` after loudnorm (which upsamples to 192 kHz
            //    internally); restore the source's own sample rate (no ffprobe here — MediaExtractor).
            //  - The source's duration (also from MediaExtractor) is the progress denominator when the
            //    queued spec carries none (rows saved by an older build), so progress is determinate.
            // The probe blocks on file I/O, so it stays off the collector's thread.
            val info = withContext(Dispatchers.IO) { probe(job.inputPath) }
            // A row saved by 1.0.3 or older carries the old upscaling `scale=-2:H`: repaired here, as the persisted args stay byte-exact.
            val rescaled = com.sieve.transcode.args.ScaleFilter.upgradeLegacy(job.presetArgs)
            val sanitized = com.sieve.transcode.args.MediaCodecSanitizer.sanitize(rescaled, info?.height, info?.width)
            val adapted = job.copy(
                inputArgs = SourceProbe.requiredInputArgs(info),
                presetArgs = com.sieve.transcode.args.LoudnormRate.restore(sanitized, info?.audioSampleRate),
                totalDurationSec = job.totalDurationSec ?: info?.durationSec,
            )
            if (run.cancelRequested) {
                // Cancel/Pause landed during the probe, before any process existed to stop: don't spawn one
                // that would run to completion. 255 = what a signalled ffmpeg reports; the driver turns it
                // into the cancel because the queue stamped its reason first.
                emit(TranscodeEvent.Done(exitCode = 255, errorSummary = null, stderrTail = ""))
                return@flow
            }
            val factory = object : FfmpegProcessFactory {
                override fun start(binaryPath: String, args: List<String>): FfmpegProcess {
                    val p = delegate.start(binaryPath, args)
                    run.process = p
                    // Cancel/Pause that slipped in between the check above and now (or in the HW->SW retry
                    // gap) saw no process to stop: stop this one rather than let a cancelled job finish. SIGKILL,
                    // not SIGTERM: nothing of a cancelled run is kept, and a wedged codec ignores SIGTERM.
                    if (run.cancelRequested) p.destroyForcibly()
                    return p
                }
            }
            emitAll(
                // A run that was asked to stop is not "a hardware failure": however it dies (a cancelled hung ffmpeg dies
                // with SIGABRT as often as not) it must not be retried on the CPU.
                FfmpegRunner(factory, binaryPath, limits).run(adapted, stopRequested = { run.cancelRequested }).onEach { ev ->
                    // The process is gone once Done is out. Forget it BEFORE the collector sees Done, because
                    // the job stays RUNNING while its output is copied to storage — a Cancel/Pause in that
                    // window must find nothing to kill rather than a dead process.
                    if (ev is TranscodeEvent.Done) active.remove(id, run)
                },
            )
        } finally {
            active.remove(id, run) // two-arg: never drop a newer run of the same id
        }
    }

    override suspend fun cancel(id: String, graceMs: Long) {
        val run = active[id] ?: return
        run.cancelRequested = true
        val process = run.process ?: return // not spawned yet: run() checks the flag before and right after the spawn
        canceller.cancel(process, graceMs)
    }
}
