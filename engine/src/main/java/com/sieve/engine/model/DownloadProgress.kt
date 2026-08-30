package com.sieve.engine.model

/** Parsed progress frame. percent is a 0..1 fraction; empty fields become the em-dash sentinel. */
data class DownloadProgress(
    val percent: Float,
    val speed: String = Sentinels.DASH,
    val eta: String = Sentinels.DASH,
    val fragment: String = Sentinels.DASH,
)
