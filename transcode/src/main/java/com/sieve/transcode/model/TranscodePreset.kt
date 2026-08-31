package com.sieve.transcode.model

/**
 * One transcode preset, ported verbatim from the desktop catalog.
 *
 * - [id] is the stable key the arg-builder switches on (byte-exact, never localized).
 * - [name]/[sub] are display strings — some are intentionally misleading vs. the actual
 *   ffmpeg args (see the plan's gotchas); reproduce them exactly regardless.
 * - [ext] is load-bearing: it drives the output filename extension.
 * - [badge] is the small callout chip; `null` renders as the em-dash placeholder ("—").
 */
data class TranscodePreset(
    val id: String,
    val category: PresetCategory,
    val name: String,
    val sub: String,
    val ext: String,
    val badge: String? = null,
)
