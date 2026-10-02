package com.sieve.queue.core

sealed interface QueueEvent {
    // ---- user / repository commands ----
    data class Enqueue(val job: QueueJob) : QueueEvent
    data class Pause(val id: String) : QueueEvent
    data class Resume(val id: String) : QueueEvent
    /** Resume each named row that is PAUSED, as one change ("Resume all" on the restored rows). */
    data class ResumeMany(val ids: Set<String>) : QueueEvent
    data class Cancel(val id: String) : QueueEvent
    data class Retry(val id: String) : QueueEvent            // manual: reset to QUEUED, attempt++
    data class Remove(val id: String) : QueueEvent
    /** Drops every COMPLETED / FAILED / CANCELLED row; queued, running and paused rows are never touched. */
    data object ClearFinished : QueueEvent
    data class SetGlobalPaused(val paused: Boolean) : QueueEvent
    data class SetMaxDownloads(val n: Int) : QueueEvent
    data class SetMaxTranscodes(val n: Int) : QueueEvent
    data class Reorder(val id: String, val beforeId: String?) : QueueEvent
    data class SetPinned(val id: String, val pinned: Boolean) : QueueEvent

    // ---- selection result from NextItemSelector (Task 6) ----
    data class MarkPreparing(val ids: List<String>) : QueueEvent
    data class MarkRunning(val id: String) : QueueEvent      // PREPARING -> RUNNING after prepare()

    // ---- normalized signals from drivers (Task 11) ----
    data class Signal(val signal: JobSignal) : QueueEvent

    // ---- recorded by the manager once finalize has moved the output into user storage ----
    /** Where the finished file ended up: a content Uri when the sink knows one, else a display path. */
    data class OutputSaved(val id: String, val location: String) : QueueEvent

    // ---- timers / lifecycle ----
    data class AutoRetryFired(val id: String) : QueueEvent   // 5 s backoff elapsed (drain trigger)
    data object Rehydrate : QueueEvent                       // process restart: in-flight -> QUEUED
    /** Rehydrate, but the unfinished rows in [heldIds] come back PAUSED instead (the one-time "restore paused" migration). */
    data class RehydrateHeld(val heldIds: Set<String>) : QueueEvent
}
