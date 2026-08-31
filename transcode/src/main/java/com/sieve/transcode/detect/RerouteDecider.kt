package com.sieve.transcode.detect

/** A transcode job already running, as the reroute logic sees it. */
data class RunningJob(val runEncoder: String?, val progress: Double, val state: String)

/**
 * Decides whether a running job's encoder is mismatched against a newly-selected encoder, and thus
 * should be rerouted. Never reroute a job past [PROGRESS_FLOOR] — throwing away >50% of a transcode
 * is worse than finishing on the "wrong" encoder.
 *
 * The desktop's cross-GPU (nvenc→qsv) reroute collapses away here: Android has one HW backend, so
 * the only transitions are cpu↔hw, plus cpu→hw being allowed when hybrid (CPU spillover) is on.
 */
object RerouteDecider {

    const val PROGRESS_FLOOR = 0.5

    fun isMismatched(job: RunningJob, newId: String, hybrid: Boolean): Boolean {
        if (job.state != "transcoding" || job.runEncoder == null || job.progress >= PROGRESS_FLOOR) return false
        val run = job.runEncoder
        val matched = if (run == "cpu") {
            newId == "cpu" || (newId.startsWith("hw") && hybrid)
        } else {
            run == newId
        }
        return !matched
    }
}

/** Default per-encoder concurrency + CPU-spillover ceilings. */
object ConcurrencyPlanner {

    /** HW is conservative (2; the real ceiling comes from `getMaxSupportedInstances` in the android layer). */
    fun defaultLimit(encoderId: String, cores: Int): Int =
        if (encoderId.startsWith("hw")) 2 else maxOf(1, cores / 2)

    /** How many CPU jobs may run alongside a HW job when hybrid spillover is enabled (0 otherwise). */
    fun cpuFallbackMax(hybrid: Boolean, encoderId: String, cores: Int): Int =
        if (hybrid && encoderId.startsWith("hw")) maxOf(1, cores / 2) else 0
}
