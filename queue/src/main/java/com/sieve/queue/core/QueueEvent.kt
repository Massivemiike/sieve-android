package com.sieve.queue.core

sealed interface QueueEvent {
    // ---- user / repository commands ----
    data class Enqueue(val job: QueueJob) : QueueEvent
    data class Pause(val id: String) : QueueEvent
    data class Resume(val id: String) : QueueEvent
    data class Cancel(val id: String) : QueueEvent
    data class Retry(val id: String) : QueueEvent            // manual: reset to QUEUED, attempt++
    data class Remove(val id: String) : QueueEvent
    data class SetGlobalPaused(val paused: Boolean) : QueueEvent
    data class SetMaxDownloads(val n: Int) : QueueEvent
    data class Reorder(val id: String, val beforeId: String?) : QueueEvent
    data class SetPinned(val id: String, val pinned: Boolean) : QueueEvent

    // ---- selection result from NextItemSelector (Task 6) ----
    data class MarkPreparing(val ids: List<String>) : QueueEvent
    data class MarkRunning(val id: String) : QueueEvent      // PREPARING -> RUNNING after prepare()

    // ---- normalized signals from drivers (Task 11) ----
    data class Signal(val signal: JobSignal) : QueueEvent

    // ---- timers / lifecycle ----
    data class AutoRetryFired(val id: String) : QueueEvent   // 5 s backoff elapsed (drain trigger)
    data object Rehydrate : QueueEvent                       // process restart: in-flight -> QUEUED
}
