package com.sieve.storage.naming

data class ClassifiedOutput(val primary: String, val sidecars: List<String>)

object ProducedFiles {

    private val VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "flv", "ts")
    private val AUDIO = setOf("m4a", "mp3", "opus", "ogg", "flac", "wav", "aac")
    private val SUBS = setOf("srt", "vtt", "ass", "ssa")
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp")

    private val SCRATCH_SUFFIX = listOf(".part", ".ytdl", ".temp")
    private val FRAG = Regex(""".*\.part-Frag\d+.*""", RegexOption.IGNORE_CASE)
    private val FITAG = Regex(""".*\.f\d+\..+""", RegexOption.IGNORE_CASE)   // video.f137.mp4
    // An unmerged stream: "<name>.f<format_id>.<media ext>" with ids like 251-drc, hls-720p, 1415483346858418v.
    // Media extensions only, so subtitle languages that start with "f" (.fr.vtt, .fa.srt) stay sidecars.
    private val FORMAT_STREAM = Regex("""\.f[^.\s/\\]+\.([A-Za-z0-9]+)$""")
    private val STREAM_EXT = VIDEO + AUDIO + setOf("m4v", "3gp", "weba", "mka")
    private val TEMP_MID = Regex(""".*\.temp\..+""", RegexOption.IGNORE_CASE) // x.temp.jpg

    fun isScratch(name: String): Boolean {
        val lower = name.lowercase()
        if (SCRATCH_SUFFIX.any { lower.endsWith(it) }) return true
        if (FRAG.matches(name)) return true
        if (FITAG.matches(name)) return true
        if (FORMAT_STREAM.find(name)?.let { it.groupValues[1].lowercase() in STREAM_EXT } == true) return true
        if (TEMP_MID.matches(name)) return true
        return false
    }

    /** Strip trailing known sidecar/double extensions to expose the shared stem. */
    fun stemOf(name: String): String {
        val s0 = name
        if (s0.endsWith(".info.json", ignoreCase = true)) return s0.dropLast(".info.json".length)
        val dot = s0.lastIndexOf('.')
        if (dot <= 0) return s0
        val s = s0.substring(0, dot)
        val dot2 = s.lastIndexOf('.')
        if (dot2 > 0) {
            val tag = s.substring(dot2 + 1)
            if (tag.length in 2..5 && tag.all { it.isLetter() || it == '-' }) {
                return s.substring(0, dot2)
            }
        }
        return s
    }

    private fun rank(name: String): Int {
        val ext = MimeMapper.extensionOf(name)
        return when {
            ext in VIDEO -> 0
            ext in AUDIO -> 1
            ext in SUBS -> 2
            ext in IMAGE -> 3
            name.endsWith(".info.json", true) -> 4
            else -> 3
        }
    }

    /**
     * True when [names] holds a finished video or audio file. [classify] only filters scratch, so it would
     * call a lone thumbnail / subtitle / info.json an output; a failed run must not publish those as a result.
     */
    fun hasMedia(names: List<String>): Boolean =
        names.any { !isScratch(it) && MimeMapper.extensionOf(it).let { ext -> ext in VIDEO || ext in AUDIO } }

    fun classify(names: List<String>, primaryHint: String? = null): ClassifiedOutput? {
        val real = names.filterNot { isScratch(it) }
        if (real.isEmpty()) return null

        val primary = when {
            primaryHint != null && primaryHint in real -> primaryHint
            else -> real.minWithOrNull(compareBy({ rank(it) }, { it.lowercase() }))!!
        }
        val sidecars = real.filter { it != primary }
        return ClassifiedOutput(primary, sidecars)
    }
}
