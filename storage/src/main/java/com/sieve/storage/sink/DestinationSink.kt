package com.sieve.storage.sink

import java.io.InputStream

/** A written (committed or pending) output. [uri] is opaque to the provider (content:// or file://). */
data class OutputTarget(
    val name: String,
    val uri: String,
    val relativeDisplay: String, // "<label-or-base>/<name>" for FinalLocation.displayPath
)

/**
 * The write destination behind an interface, so [com.sieve.storage.service.SafOutputProvider] logic
 * is JVM-testable over a fake. Real impls: SAF DocumentFile tree, MediaStore Downloads, app-files.
 */
interface DestinationSink {
    /** Names already present in the destination dir for [dirLabel] (for collision math). */
    suspend fun existingNames(dirLabel: String?): Set<String>

    /** Create a pending output and stream [bytes] into it. Returns the committed-or-pending target. */
    suspend fun write(dirLabel: String?, name: String, mime: String, bytes: InputStream): OutputTarget

    /** Publish a pending target (MediaStore IS_PENDING=0 / SAF temp rename). No-op if already committed. */
    suspend fun commit(target: OutputTarget)

    /** Roll back a pending/partial target (delete). Tolerates already-gone. */
    suspend fun deletePending(target: OutputTarget)

    /** Human label of this sink's root for FinalLocation display. */
    val rootLabel: String
}
