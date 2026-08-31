package com.sieve.storage.library

/** Matches a subtitle sidecar for [primaryName] among already-listed [siblings] (no extra I/O). */
object SubtitleMatcher {
    private val EXTS = listOf("srt", "vtt", "ass", "ssa") // priority order
    private val LANGS = listOf("", "en", "en-US", "en-GB", "es", "fr", "de", "ja", "zh", "pt")

    fun findSidecar(primaryName: String, siblings: List<String>): String? {
        val base = primaryName.substringBeforeLast('.', primaryName)
        val set = siblings.toHashSet()
        for (ext in EXTS) {
            for (lang in LANGS) {
                val candidate = if (lang.isEmpty()) "$base.$ext" else "$base.$lang.$ext"
                if (candidate in set) return candidate
            }
        }
        return null
    }
}
