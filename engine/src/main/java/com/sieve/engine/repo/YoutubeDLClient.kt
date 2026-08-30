package com.sieve.engine.repo

/** Result of running yt-dlp once. */
data class ExecResult(val exitCode: Int, val out: String, val err: String)

/**
 * Thin seam over youtubedl-android, using engine-owned types (not library types)
 * so YtDlpEngineImpl is fully unit-testable with fakes. The real implementation
 * ([YoutubeDLClientImpl]) is the only place that touches the library.
 */
interface YoutubeDLClient {
    fun version(): String?
    /** Runs yt-dlp with a flat token list; `onProgress` receives (progress, eta, line). */
    fun execute(processId: String, url: String, options: List<String>, onProgress: (Float, Long, String) -> Unit): ExecResult
    fun destroy(processId: String): Boolean
    /** Applies the engine update; returns the status name (e.g. "DONE"/"ALREADY_UP_TO_DATE"). */
    fun update(nightly: Boolean): String
}
