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
    /**
     * Args placed BEFORE `-i` (input options), e.g. `-c:v av1_mediacodec` to hardware-decode AV1
     * sources — the bundled ffmpeg has no working software AV1 decoder (no dav1d/libaom), so
     * without this an AV1 input decodes zero frames and the job dies with "Conversion failed!".
     * Runtime-only (injected at spawn by the port); never persisted.
     */
    val inputArgs: List<String> = emptyList(),
)
