package com.sieve.engine.repo

import com.sieve.engine.update.YtDlpRecordGuard

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

    /**
     * Forgets the library's record of its yt-dlp when that record names a newer yt-dlp than the file on disk (the
     * library's updater trusts the record and would answer "up to date" without downloading). Returns what was
     * repaired, null when nothing was. Never throws. Blocking, cheap (reads ~170 KB), no process is started.
     */
    fun repairVersionRecord(): YtDlpRecordGuard.Repair? = null
}
