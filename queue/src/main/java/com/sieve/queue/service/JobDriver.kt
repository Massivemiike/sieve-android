package com.sieve.queue.service

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
 * encoding the terminal asymmetries: `Completed(exit≠0)` → Failed; transcode `Done(nonzero)` is a
 * cancel when a `cancelReason` is present else a failure; ffmpeg progress → indeterminate when the
 * duration is null. First terminal is authoritative — later events are dropped.
 *
 * `cancelReasonSupplier` is read when a terminal cancel is seen; the QueueManager stamps the reason
 * before killing, so it is present by the time Cancelled/Done arrives.
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
        downloadPort.download(job.id, spec.url, spec.engineArgs).collect { ev ->
            if (terminated) return@collect
            when (ev) {
                is EngineEvent.Progress -> emit(JobSignal.Progress(job.id, ProgressMapper.fromDownload(ev.progress)))
                is EngineEvent.Log -> emit(JobSignal.Log(job.id, ev.line, ev.isError, ev.filePath))
                is EngineEvent.Completed -> {
                    terminated = true
                    emit(
                        JobSignal.Terminal(
                            job.id,
                            if (ev.exitCode == 0) Outcome.Succeeded
                            else Outcome.Failed(FailureInfo("yt-dlp exited ${ev.exitCode}", exitCode = ev.exitCode)),
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

    private fun driveTranscode(job: QueueJob, spec: JobSpec.Transcode, reason: () -> CancelReason?): Flow<JobSignal> = flow {
        var terminated = false
        // outputPath is filled by the QueueManager before spawn; the driver receives the prepared job.
        val tj = TranscodeJob(spec.inputPath, "", spec.presetArgs, spec.totalDurationSec, spec.usedHardwareEncoder)
        transcodePort.run(job.id, tj).collect { ev ->
            if (terminated) return@collect
            when (ev) {
                is TranscodeEvent.Progress -> emit(JobSignal.Progress(job.id, ProgressMapper.fromFfmpeg(ev.progress, spec.totalDurationSec)))
                is TranscodeEvent.Log -> emit(JobSignal.Log(job.id, ev.line, ev.isError))
                is TranscodeEvent.Done -> {
                    terminated = true
                    val r = reason()
                    val outcome = when {
                        ev.exitCode == 0 -> Outcome.Succeeded
                        r != null -> Outcome.Cancelled(r)
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
