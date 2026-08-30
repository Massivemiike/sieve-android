package com.sieve.engine.repo

import com.sieve.engine.model.DownloadProgress

/** The single canonical event type a download Flow emits. */
sealed interface EngineEvent {
    data class Progress(val progress: DownloadProgress) : EngineEvent
    data class Log(val line: String, val filePath: String?, val isError: Boolean) : EngineEvent
    /** Terminal: process finished; exitCode may be non-zero (routed to ErrorMapper). */
    data class Completed(val exitCode: Int) : EngineEvent
    /** Terminal: genuine spawn/init failure. */
    data class Failed(val error: String) : EngineEvent
    /** Terminal: user pause/cancel (swallowed by the collector). */
    data object Cancelled : EngineEvent
}
