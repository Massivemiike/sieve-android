package com.sieve.storage.library

/**
 * Pure ffmpeg arg vector for single-frame extraction: `-y -ss <t> -i <in> -frames:v 1 -q:v 2 <out>`.
 * `-ss` before `-i` = fast input seek. Whole-second timestamps render without a trailing `.0`.
 */
object FrameExtractorArgs {
    fun build(inputFdPath: String, outputFdPath: String, timeSec: Double): List<String> {
        val ss = if (timeSec % 1.0 == 0.0) timeSec.toLong().toString() else timeSec.toString()
        return listOf("-y", "-ss", ss, "-i", inputFdPath, "-frames:v", "1", "-q:v", "2", outputFdPath)
    }
}

/**
 * Extracts a PNG frame from a DocumentStore-hosted video into a sibling `<base>_frame_<ms>.png`.
 * Reuses the bundled ffmpeg binary via an injected [exec] (a one-shot call over `/proc/self/fd/N`
 * targets — the real transcode FfmpegRunner forces `-i` before its args and tracks progress, which
 * doesn't fit `-ss`-before-`-i` frame grabs, so this execs ffmpeg directly).
 */
class FrameExtractor(
    private val ffmpegBinaryPath: String,
    private val store: DocumentStore,
    private val exec: suspend (List<String>) -> Int,
    private val fdPath: (Int) -> String = { "/proc/self/fd/$it" },
) {
    suspend fun extract(parentUri: String, sourceUri: String, baseName: String, timeSec: Double): LibraryEntry? {
        val ms = Math.round(timeSec * 1000)
        val stem = baseName.substringBeforeLast('.', baseName)
        val outName = "${stem}_frame_$ms.png"
        val outEntry = store.createChild(parentUri, "image/png", outName) ?: return null
        val inFd = store.openReadFd(sourceUri)
        val outFd = store.openWriteFd(outEntry.uri)
        val args = FrameExtractorArgs.build(fdPath(inFd), fdPath(outFd), timeSec)
        val code = exec(listOf(ffmpegBinaryPath) + args)
        return if (code == 0) outEntry.copy(name = outName) else null
    }
}
