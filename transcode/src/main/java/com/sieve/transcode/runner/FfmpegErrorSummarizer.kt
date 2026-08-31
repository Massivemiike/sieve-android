package com.sieve.transcode.runner

/**
 * Turns raw ffmpeg stderr into a one-line human summary via ordered rules (first match wins).
 *
 * Rule order is load-bearing: the MediaCodec hardware-init cluster (rule 1) must be tested before
 * the generic fallback so a `Cannot open encoder` never degrades to a raw stderr line.
 * [isHardwareEncoderInitFailure] gates the HW→SW retry in the runner (Task 17).
 */
object FfmpegErrorSummarizer {

    private val HW_INIT = Regex(
        "(?i)Cannot open encoder" +
            "|Failed to (initialize|configure) MediaCodec" +
            "|mediacodec.*(error|failed)" +
            "|Could not (open|find) codec",
    )
    private val UNKNOWN_ENCODER = Regex("Unknown encoder '([^']+)'")
    private val DISK_FULL = Regex("(?i)disk.*full|No space left")
    private val FALLBACK = Regex("(?i)error|failed|invalid|cannot|unable|denied")

    fun isHardwareEncoderInitFailure(stderr: String): Boolean = HW_INIT.containsMatchIn(stderr)

    fun summarize(stderr: String): String {
        if (HW_INIT.containsMatchIn(stderr))
            return "Hardware (MediaCodec) encoder failed — retrying on software (libx264/libx265)."
        if (stderr.contains("No such file or directory")) return "Input file not found or inaccessible"
        if (stderr.contains("Permission denied")) return "Permission denied — output folder not writable"
        UNKNOWN_ENCODER.find(stderr)?.let {
            return "Unknown encoder '${it.groupValues[1]}' — switch to a different encoder"
        }
        if (stderr.contains("Invalid argument")) return "Invalid ffmpeg arguments — check preset/raw args"
        if (stderr.contains("already exists")) return "Output file already exists"
        if (DISK_FULL.containsMatchIn(stderr)) return "Disk full"

        val lines = stderr.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        return lines.lastOrNull { FALLBACK.containsMatchIn(it) }
            ?: lines.firstOrNull()
            ?: "Unknown ffmpeg failure"
    }
}
