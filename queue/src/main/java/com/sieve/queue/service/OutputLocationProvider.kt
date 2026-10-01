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

    /**
     * Drops whatever work-dir leftovers a job has (partial files of a paused-then-cancelled job,
     * a failed finalize), without the caller holding its [PreparedOutput]. The work path is stable
     * per job id and [prepare] only creates it, so prepare-then-discard is safe and never touches the
     * finished output, which lives in the destination sink.
     */
    suspend fun cleanup(job: QueueJob) = discard(job, prepare(job))
}

/** Injectable wall clock. */
interface Clock {
    fun nowMs(): Long
}
