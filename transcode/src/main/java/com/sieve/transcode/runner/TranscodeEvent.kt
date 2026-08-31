package com.sieve.transcode.runner

/** Stream of events a running transcode produces: incremental progress, log lines, and a terminal Done. */
sealed interface TranscodeEvent {
    data class Progress(val progress: FfmpegProgress) : TranscodeEvent
    data class Log(val line: String, val isError: Boolean) : TranscodeEvent
    data class Done(val exitCode: Int, val errorSummary: String?, val stderrTail: String) : TranscodeEvent
}
