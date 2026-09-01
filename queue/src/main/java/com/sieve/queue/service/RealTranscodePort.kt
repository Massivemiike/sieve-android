package com.sieve.queue.service

import com.sieve.transcode.runner.FfmpegProcess
import com.sieve.transcode.runner.FfmpegProcessFactory
import com.sieve.transcode.runner.FfmpegRunner
import com.sieve.transcode.runner.TranscodeEvent
import com.sieve.transcode.runner.TranscodeJob
import com.sieve.transcode.runner.android.AndroidFfmpegProcessFactory
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * Production [TranscodePort] over [FfmpegRunner]. The queue owns the process factory: it wraps the
 * real [AndroidFfmpegProcessFactory] to record each spawned [FfmpegProcess] by job id, so
 * [cancel] can reach `runner.cancel(process, graceMs)`.
 *
 * Reconciled to the real transcode API (the plan sketched a `(cmd) -> java.lang.Process` factory;
 * the module's real seam is `FfmpegProcessFactory` returning a `FfmpegProcess`).
 */
class RealTranscodePort(binaryPath: String) : TranscodePort {
    private val processes = ConcurrentHashMap<String, FfmpegProcess>()
    private val delegate = AndroidFfmpegProcessFactory()

    @Volatile private var currentId: String? = null

    private val capturingFactory = object : FfmpegProcessFactory {
        override fun start(binaryPath: String, args: List<String>): FfmpegProcess {
            val p = delegate.start(binaryPath, args)
            currentId?.let { processes[it] = p }
            return p
        }
    }

    private val runner = FfmpegRunner(capturingFactory, binaryPath)

    override fun run(id: String, job: TranscodeJob): Flow<TranscodeEvent> {
        currentId = id
        // Spawn-time source adaptation (persisted preset args stay byte-exact):
        //  - AV1 inputs must hardware-decode (`-c:v av1_mediacodec` before -i) — the bundled ffmpeg
        //    has no working software AV1 decoder, so they otherwise fail with 0 frames encoded.
        //  - MediaCodec encoders ignore -crf/-preset and default to ~200 kbps: the sanitizer strips
        //    them and injects a height/CRF-derived -b:v.
        val info = com.sieve.transcode.runner.android.SourceProbe.probe(job.inputPath)
        val adapted = job.copy(
            inputArgs = com.sieve.transcode.runner.android.SourceProbe.requiredInputArgs(info),
            presetArgs = com.sieve.transcode.args.MediaCodecSanitizer.sanitize(job.presetArgs, info?.height),
        )
        return runner.run(adapted)
    }

    override suspend fun cancel(id: String, graceMs: Long) {
        processes[id]?.let { runner.cancel(it, graceMs) }
        processes.remove(id)
    }
}
