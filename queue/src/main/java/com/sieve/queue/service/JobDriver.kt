package com.sieve.queue.service

import com.sieve.engine.parse.YtdlpErrors
import com.sieve.engine.repo.EngineEvent
import com.sieve.queue.core.CancelReason
import com.sieve.queue.core.FailureInfo
import com.sieve.queue.core.JobSignal
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.Outcome
import com.sieve.queue.core.ProgressMapper
import com.sieve.queue.core.QueueJob
import com.sieve.transcode.runner.TranscodeEvent
import com.sieve.transcode.runner.TranscodeJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Collapses the two module cold flows (`EngineEvent` / `TranscodeEvent`) into one `JobSignal` stream,
 * encoding the terminal asymmetries: `Completed(exit≠0)` → Failed; a transcode `Done` is a cancel
 * whenever a `cancelReason` is present (whatever its exit code), else Succeeded/Failed by exit code;
 * ffmpeg progress → indeterminate when the duration is null. First terminal is authoritative — later
 * events are dropped.
 *
 * `cancelReasonSupplier` is read when a terminal cancel is seen; the QueueManager stamps the reason
 * before killing, so it is present by the time Cancelled/Done arrives. For a transcode the reason is
 * checked BEFORE the exit code: Cancel/Pause asks ffmpeg to quit with `q`, which it treats as a normal
 * stop — it finalizes the truncated output and exits 0, which must not read as a finished job.
 */
class JobDriver(
    private val downloadPort: DownloadPort,
    private val transcodePort: TranscodePort,
) {
    fun drive(job: QueueJob, cancelReasonSupplier: () -> CancelReason?): Flow<JobSignal> = when (val spec = job.spec) {
        is JobSpec.Download -> driveDownload(job, spec, cancelReasonSupplier)
        is JobSpec.Transcode -> driveTranscode(job, spec, cancelReasonSupplier)
    }

    private fun driveDownload(job: QueueJob, spec: JobSpec.Download, reason: () -> CancelReason?): Flow<JobSignal> = flow {
        var terminated = false
        // The engine reports a failed run as ONE isError Log carrying yt-dlp's whole stderr, then
        // Completed(1). Keep it so the failed job carries the real error, not just the exit code.
        var errorBlob: String? = null
        downloadPort.download(job.id, spec.url, spec.engineArgs).collect { ev ->
            if (terminated) return@collect
            when (ev) {
                is EngineEvent.Progress -> emit(JobSignal.Progress(job.id, ProgressMapper.fromDownload(ev.progress)))
                is EngineEvent.Log -> {
                    if (ev.isError) errorBlob = ev.line
                    emit(JobSignal.Log(job.id, ev.line, ev.isError, ev.filePath))
                }
                is EngineEvent.Completed -> {
                    terminated = true
                    emit(
                        JobSignal.Terminal(
                            job.id,
                            if (ev.exitCode == 0) Outcome.Succeeded else Outcome.Failed(downloadFailure(ev.exitCode, errorBlob)),
                        ),
                    )
                }
                is EngineEvent.Failed -> {
                    terminated = true
                    emit(JobSignal.Terminal(job.id, Outcome.Failed(FailureInfo(ev.error))))
                }
                EngineEvent.Cancelled -> {
                    terminated = true
                    emit(JobSignal.Terminal(job.id, Outcome.Cancelled(reason() ?: CancelReason.USER_CANCEL)))
                }
            }
        }
    }

    /**
     * The message stays RAW (yt-dlp's own ERROR lines, not humanized) so [com.sieve.queue.core.RetryClassifier]
     * sees the real signals (429, network, ...); the UI humanizes it for display. Only `ERROR:` lines are
     * kept — WARNING lines would otherwise leak into the verdict. The full blob tail rides in `stderrTail`;
     * the classifier reads only the ERROR lines of it too, so a WARNING there decides nothing either.
     */
    private fun downloadFailure(exitCode: Int, blob: String?): FailureInfo {
        val text = blob.orEmpty()
        val message = YtdlpErrors.errorLines(text)
            .ifBlank { text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty() }
            .take(500)
            .ifBlank { "yt-dlp exited $exitCode" }
        return FailureInfo(message = message, exitCode = exitCode, stderrTail = text.takeLast(4000).ifEmpty { null })
    }

    private fun driveTranscode(job: QueueJob, spec: JobSpec.Transcode, reason: () -> CancelReason?): Flow<JobSignal> = flow {
        var terminated = false
        // outputPath is filled by the QueueManager before spawn; the driver receives the prepared job.
        val tj = TranscodeJob(spec.inputPath, spec.outputPath, spec.presetArgs, spec.totalDurationSec, spec.usedHardwareEncoder)
        transcodePort.run(job.id, tj).collect { ev ->
            if (terminated) return@collect
            when (ev) {
                is TranscodeEvent.Progress -> emit(JobSignal.Progress(job.id, ProgressMapper.fromFfmpeg(ev.progress, spec.totalDurationSec)))
                is TranscodeEvent.Log -> emit(JobSignal.Log(job.id, ev.line, ev.isError))
                is TranscodeEvent.Done -> {
                    terminated = true
                    val r = reason()
                    val outcome = when {
                        r != null -> Outcome.Cancelled(r)
                        ev.exitCode == 0 -> Outcome.Succeeded
                        else -> Outcome.Failed(
                            FailureInfo(ev.errorSummary ?: "ffmpeg exited ${ev.exitCode}", exitCode = ev.exitCode, stderrTail = ev.stderrTail),
                        )
                    }
                    emit(JobSignal.Terminal(job.id, outcome))
                }
            }
        }
    }
}
