package com.sieve.transcode.args

/**
 * Per-encoder `-threads` caps, ported from the desktop `ENCODER_THREAD_CAPS` + `capThreadsForEncoder`.
 *
 * The load-bearing entry is `libx265 → 16`: x265's `X265_MAX_FRAME_THREADS` is 16, and passing 17+
 * makes the encoder refuse to open (`frameNumThreads must be [0 .. 16)`). This is smoke-locked.
 *
 * A cap of `0` means "never emit `-threads`" (hardware encoders and stream copy ignore it). The
 * Android port adds `h264_mediacodec`/`hevc_mediacodec` at 0 for the same reason.
 */
object ThreadCaps {

    val CAPS: Map<String, Int> = mapOf(
        "libx265" to 16,
        "libx264" to 16,
        "h264_nvenc" to 0,
        "hevc_nvenc" to 0,
        "av1_nvenc" to 0,
        "h264_qsv" to 0,
        "hevc_qsv" to 0,
        "av1_qsv" to 0,
        "h264_amf" to 0,
        "hevc_amf" to 0,
        "copy" to 0,
        // Android additions (MediaCodec ignores -threads):
        "h264_mediacodec" to 0,
        "hevc_mediacodec" to 0,
    )

    /**
     * Resolve the `-threads` value for these args.
     * - No `-c:v` (audio presets), or `-c:v` with no following token → `0` (also covers `gif`/`webp-anim`,
     *   which use `-vf`/`-vcodec` and carry no `-c:v`).
     * - Capped codec → `0` when its cap is 0, else `min(requested, cap)`.
     * - Uncapped-but-present codec (libsvtav1, libvpx-vp9, prores_ks, dnxhd, mpeg2video) → `min(requested, 16)`.
     */
    fun capThreadsForEncoder(args: List<String>, requested: Int): Int {
        val cIdx = args.indexOf("-c:v")
        if (cIdx < 0 || cIdx + 1 >= args.size || args[cIdx + 1].isEmpty()) return 0
        val codec = args[cIdx + 1]
        val cap = CAPS[codec]
        return when {
            cap == null -> minOf(requested, 16)
            cap == 0 -> 0
            else -> minOf(requested, cap)
        }
    }
}
