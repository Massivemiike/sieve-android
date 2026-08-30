package com.sieve.engine.model

/** Download lifecycle states. `wire` is the persisted/JSON string. */
enum class DownloadState(val wire: String) {
    ANALYZING("analyzing"),
    DOWNLOADING("downloading"),
    QUEUED("queued"),
    PAUSED("paused"),
    POSTPROCESS("postprocess"),
    DONE("done"),
    ERROR("error");

    val isTerminal: Boolean get() = this == DONE || this == ERROR

    companion object {
        fun from(s: String): DownloadState = entries.first { it.wire == s }
    }
}
