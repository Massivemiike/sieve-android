package com.sieve.queue.service

import com.sieve.queue.core.FinalLocation
import com.sieve.queue.core.PreparedOutput
import com.sieve.queue.core.QueueJob

/**
 * The output-location seam. The queue never touches SAF/MediaStore directly — plan #4 (storage)
 * provides the real implementation. [prepare] resolves a work path before a job spawns; [finalize]
 * moves the finished output to its destination; [discard] cleans up a failed/cancelled work dir.
 */
interface OutputLocationProvider {
    suspend fun prepare(job: QueueJob): PreparedOutput
    suspend fun finalize(job: QueueJob, prepared: PreparedOutput): FinalLocation
    suspend fun discard(job: QueueJob, prepared: PreparedOutput)
}

/** Injectable wall clock. */
interface Clock {
    fun nowMs(): Long
}
