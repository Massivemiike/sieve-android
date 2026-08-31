package com.sieve.transcode.runner

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs a [TranscodeJob] via a [FfmpegProcessFactory], turning the process's stdout/stderr into a
 * cold [Flow] of [TranscodeEvent]. stdout feeds [FfmpegProgressParser] (emitting `Progress` only for
 * `outTimeUs > 0`, filtering ffmpeg's startup 0-blocks); stderr is cleaned (ANSI-stripped, capped)
 * and emitted as `Log`, while a 64 KB tail is kept for the error summary.
 *
 * On a hardware-encoder init failure (exit != 0 on a HW job whose stderr matches
 * [FfmpegErrorSummarizer.isHardwareEncoderInitFailure]) the runner retries **once** with the codec
 * demoted to software.
 */
class FfmpegRunner(
    private val factory: FfmpegProcessFactory,
    private val binaryPath: String,
) {

    fun run(job: TranscodeJob): Flow<TranscodeEvent> = channelFlow {
        var current = job
        var retried = false
        while (true) {
            val process = factory.start(binaryPath, buildFullArgs(current))
            val parser = FfmpegProgressParser(current.totalDurationSec)
            val tail = StringBuilder()
            val lastLines = ArrayDeque<String>()

            val outJob = launch {
                process.stdout.collect { chunk ->
                    parser.onChunk(chunk).forEach { p ->
                        if (p.outTimeUs > 0) send(TranscodeEvent.Progress(p))
                    }
                }
            }
            val errJob = launch {
                process.stderr.collect { raw ->
                    val clean = ANSI.replace(raw, "").take(500)
                    tail.append(clean).append('\n')
                    if (tail.length > STDERR_MAX) tail.delete(0, tail.length - STDERR_MAX)
                    lastLines.addLast(clean)
                    while (lastLines.size > 30) lastLines.removeFirst()
                    send(TranscodeEvent.Log(clean, ERROR_LINE.containsMatchIn(clean)))
                }
            }

            val code = process.awaitExit()
            outJob.join()
            errJob.join()
            val tailStr = tail.toString()

            val hwInitFailed = !retried && current.usedHardwareEncoder && code != 0 &&
                FfmpegErrorSummarizer.isHardwareEncoderInitFailure(tailStr)
            if (hwInitFailed) {
                retried = true
                send(TranscodeEvent.Log("HW encoder failed, retrying on software encoder", false))
                current = current.copy(
                    presetArgs = demoteToSoftware(current.presetArgs),
                    usedHardwareEncoder = false,
                )
                continue
            }

            send(
                TranscodeEvent.Done(
                    exitCode = code,
                    errorSummary = if (code != 0) FfmpegErrorSummarizer.summarize(tailStr) else null,
                    stderrTail = lastLines.joinToString("\n"),
                ),
            )
            break
        }
    }

    /** Graceful cancel: ask ffmpeg to quit (`q`), then SIGTERM if it hasn't exited within [graceMs]. */
    suspend fun cancel(process: FfmpegProcess, graceMs: Long = 2000) {
        process.writeStdin("q")
        val exited = withTimeoutOrNull(graceMs) { process.awaitExit() }
        if (exited == null) process.destroy()
    }

    companion object {
        const val STDERR_MAX = 65536
        val ERROR_LINE = Regex("error|failed|invalid|cannot|unable|denied", RegexOption.IGNORE_CASE)
        private val ANSI = Regex("\\[[0-9;]*[A-Za-z]")

        fun buildFullArgs(job: TranscodeJob): List<String> =
            listOf("-y", "-progress", "pipe:1", "-i", job.inputPath) + job.presetArgs + listOf(job.outputPath)

        /**
         * Swap the video codec token after `-c:v` from MediaCodec to its software counterpart.
         *
         * Codec swap only, per plan. Re-applying an explicit `-threads` cap after demotion
         * (invariant #16) needs the requested-thread count, which [TranscodeJob] does not carry; the
         * queue layer (a later plan) owns that. Omitting it is safe — ffmpeg auto-threads libx264/
         * libx265 within x265's 16-thread limit when `-threads` is absent.
         */
        fun demoteToSoftware(args: List<String>): List<String> {
            val cIdx = args.indexOf("-c:v")
            if (cIdx < 0 || cIdx + 1 >= args.size) return args
            val out = args.toMutableList()
            out[cIdx + 1] = when (out[cIdx + 1]) {
                "h264_mediacodec" -> "libx264"
                "hevc_mediacodec" -> "libx265"
                else -> out[cIdx + 1]
            }
            return out
        }
    }
}
