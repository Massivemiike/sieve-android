package com.sieve.engine.model

/** Pure state projections the queue/persistence layers apply (ported from downloadStore). */
object DownloadStateMachine {
    fun mapExit(exitCode: Int): DownloadState =
        if (exitCode == 0) DownloadState.DONE else DownloadState.ERROR

    /** In-flight states persist as QUEUED (resume on restart). */
    fun mapPersistState(s: DownloadState): DownloadState =
        if (s == DownloadState.DOWNLOADING || s == DownloadState.POSTPROCESS) DownloadState.QUEUED else s

    fun mapPersistProgress(s: DownloadState): Float = if (s == DownloadState.DONE) 1f else 0f

    /** On restore, terminal states are kept; everything else becomes QUEUED. */
    fun mapRestoreState(s: DownloadState): DownloadState =
        if (s.isTerminal) s else DownloadState.QUEUED
}
