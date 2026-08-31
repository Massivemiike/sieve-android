package com.sieve.app.di

/** Startup helpers that don't belong to the engine module. */
object EngineBootstrap {

    /**
     * Captures `ffmpeg -encoders` once so [com.sieve.transcode.detect.android.AndroidVideoEncoderProbe]
     * can gate hardware encoders on the ffmpeg wrapper actually being present. Returns "" on any
     * failure — the detector then degrades to CPU-only.
     */
    fun captureFfmpegEncoders(binaryPath: String): String = try {
        val p = ProcessBuilder(listOf(binaryPath, "-hide_banner", "-encoders"))
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out.take(200_000)
    } catch (e: Exception) {
        ""
    }
}
