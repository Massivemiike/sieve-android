package com.sieve.transcode.args

/**
 * Picks a sensible starting preset id from a probed source's codec + resolution.
 *
 * Ported 1:1 from the desktop smart-preset heuristic. Only consulted when the queue is empty;
 * the caller must still confirm the returned id exists in the catalog.
 *
 * The load-bearing quirk: **height is the SECOND number** matched in the resolution string
 * (`1920x1080` → 1080), so a single-number string (`"1080"`) yields height 0 → `null`.
 */
object SmartPreset {

    fun pick(codec: String?, resolution: String?): String? {
        val c = (codec ?: "").lowercase()
        val res = resolution ?: ""
        val audioOnly = res.isBlank() || res == "—" || Regex("^[—–-]+$").matches(res.trim())
        val hasVideo = listOf("h264", "hevc", "vp9", "av1").any { it in c }
        val hasAudio = listOf("mp3", "aac", "flac", "opus", "vorbis").any { it in c }

        if (audioOnly || (!hasVideo && hasAudio)) return when {
            "mp3" in c -> "mp3-320"
            "flac" in c -> "flac"
            "opus" in c -> "opus-160"
            else -> "aac-256"
        }

        val nums = Regex("(\\d+)").findAll(res).map { it.value }.toList()
        val height = if (nums.size >= 2) nums[1].toInt() else 0
        return when {
            height >= 2000 -> "h265-4k"
            height >= 800 -> "h265-1080"
            height >= 600 -> "h264-1080"
            else -> null
        }
    }
}
