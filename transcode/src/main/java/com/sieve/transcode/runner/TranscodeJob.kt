package com.sieve.transcode.runner

/**
 * A fully-resolved transcode to run: the input, the destination, and the preset args produced by
 * [com.sieve.transcode.args.FfmpegArgs.build] + [com.sieve.transcode.args.ArgFinalizer].
 *
 * [usedHardwareEncoder] tells the runner whether a MediaCodec-init failure should trigger the
 * one-shot HW→SW retry.
 */
data class TranscodeJob(
    val inputPath: String,
    val outputPath: String,
    val presetArgs: List<String>,
    val totalDurationSec: Double?,
    val usedHardwareEncoder: Boolean,
)
