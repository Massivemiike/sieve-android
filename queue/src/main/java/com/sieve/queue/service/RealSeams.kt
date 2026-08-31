package com.sieve.queue.service

import android.content.Context
import com.sieve.queue.core.FinalLocation
import com.sieve.queue.core.PreparedOutput
import com.sieve.queue.core.QueueJob
import java.io.File

/** Real wall clock. */
class SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * v1 output provider (NO SAF yet): a stable per-job work dir under app files, so a resumed
 * `yt-dlp -c` finds its own `.part`. Plan #4 replaces this with a SAF/MediaStore implementation
 * WITHOUT changing the queue.
 */
class RealOutputProviderV1(private val ctx: Context) : OutputLocationProvider {
    override suspend fun prepare(job: QueueJob): PreparedOutput {
        val dir = File(ctx.filesDir, "work/${job.id}").apply { mkdirs() }
        return PreparedOutput(dir.absolutePath, job.output.outputTemplate)
    }
    override suspend fun finalize(job: QueueJob, prepared: PreparedOutput): FinalLocation =
        FinalLocation(prepared.workDir, null) // v1: leave in place; plan #4 streams into Downloads/Sieve
    override suspend fun discard(job: QueueJob, prepared: PreparedOutput) {
        File(prepared.workDir).deleteRecursively()
    }
}
