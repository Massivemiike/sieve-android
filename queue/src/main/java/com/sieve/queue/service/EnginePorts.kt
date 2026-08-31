package com.sieve.queue.service

import com.sieve.engine.repo.EngineEvent
import com.sieve.transcode.runner.TranscodeEvent
import com.sieve.transcode.runner.TranscodeJob
import kotlinx.coroutines.flow.Flow

/** Seam over `YtDlpEngine.download`/`cancel`, so the QueueManager is JVM-testable via fakes. */
interface DownloadPort {
    fun download(id: String, url: String, args: List<String>): Flow<EngineEvent>
    fun cancel(id: String): Boolean
}

/** Seam over `FfmpegRunner.run`/`cancel`. */
interface TranscodePort {
    fun run(id: String, job: TranscodeJob): Flow<TranscodeEvent>
    suspend fun cancel(id: String, graceMs: Long)
}
