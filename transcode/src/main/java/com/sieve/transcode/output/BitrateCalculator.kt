package com.sieve.transcode.output

/**
 * Target-size → video bitrate math for the bitrate calculator.
 *
 * Invariant #14: this calc uses **8192** (binary MB → kbit) while [OutputEstimator] uses **1000**
 * (`*1000/8`). Two conventions live in one feature by design — do NOT unify them.
 */
object BitrateCalculator {

    /** Video kbps to hit [targetMb] over [totalDurationSec] after reserving [audioKbps]. Null if inputs invalid. */
    fun videoKbps(targetMb: Double, audioKbps: Double, totalDurationSec: Double): Int? {
        if (totalDurationSec <= 0.0 || targetMb <= 0.0 || audioKbps < 0.0) return null
        return maxOf(0, Math.round((targetMb * 8192.0) / totalDurationSec - audioKbps).toInt())
    }
}

/** Estimated output size, reduction %, and ETA — pure math for the transcode preview. */
object OutputEstimator {

    /** CRF-override scaling uses 2^((presetCrf-override)/6); only applied when both CRF values are known. */
    fun estimatedBytes(bitrateKbps: Double, dur: Double, presetCrf: Int?, override: Int?): Double {
        val scale = if (presetCrf != null && override != null)
            Math.pow(2.0, (presetCrf - override) / 6.0) else 1.0
        return (bitrateKbps * 1000.0 / 8.0) * dur * scale
    }

    fun reductionPct(est: Double, input: Double): Int =
        if (input > 0) Math.round((1 - est / input) * 100).toInt() else 0

    fun etaSeconds(dur: Double, perf: Double): Double = dur / (if (perf != 0.0) perf else 1.0)
}
