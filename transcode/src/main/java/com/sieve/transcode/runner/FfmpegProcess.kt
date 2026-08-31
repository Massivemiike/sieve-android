package com.sieve.transcode.runner

import kotlinx.coroutines.flow.Flow

/**
 * Seam over a running ffmpeg process. stdout and stderr are **separate** flows — the runner drains
 * both concurrently, and the implementation must never merge them (`redirectErrorStream(false)`),
 * because progress is parsed from stdout and pipe backpressure on a merged stream deadlocks.
 */
interface FfmpegProcess {
    val stdout: Flow<String>
    val stderr: Flow<String>
    suspend fun writeStdin(text: String)
    fun destroy()
    fun destroyForcibly()
    suspend fun awaitExit(): Int
}

/** Starts an ffmpeg process for the given binary + args. Real impl lives in the android layer (Task 18). */
interface FfmpegProcessFactory {
    fun start(binaryPath: String, args: List<String>): FfmpegProcess
}
