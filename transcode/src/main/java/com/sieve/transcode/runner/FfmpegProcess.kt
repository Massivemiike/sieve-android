package com.sieve.transcode.runner

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * Waits at most [timeoutMs] for the process to exit; true when it has. Cancel's grace period is
     * enforced through this, so a real implementation must bound the wait with a TIMED OS wait: a plain
     * blocking `waitFor()` inside `withTimeoutOrNull` is not interrupted by the timeout, and a wedged
     * ffmpeg would then never be sent SIGTERM. The default suits fakes whose [awaitExit] suspends
     * cooperatively.
     */
    suspend fun awaitExit(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { awaitExit() } != null
}

/** Starts an ffmpeg process for the given binary + args. Real impl lives in the android layer (Task 18). */
interface FfmpegProcessFactory {
    fun start(binaryPath: String, args: List<String>): FfmpegProcess
}
