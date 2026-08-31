package com.sieve.storage.library

/** Ports the desktop srtToPlainText: strips cue ids, timecodes, WEBVTT/NOTE, <tags>, {\an8} overrides. */
object SrtToPlainText {
    private val TIMECODE = Regex("""^\s*\d{1,2}:\d{2}:\d{2}[.,]\d{1,3}\s*-->.*$""")
    private val CUE_NUMBER = Regex("""^\s*\d+\s*$""")
    private val TAG = Regex("""<[^>]*>""")
    private val ASS_OVERRIDE = Regex("""\{\\[^}]*}""")

    fun convert(raw: String): String {
        val out = ArrayList<String>()
        for (lineRaw in raw.replace("\r\n", "\n").split('\n')) {
            val line = lineRaw.trim()
            if (line.isEmpty()) continue
            if (line == "WEBVTT" || line.startsWith("WEBVTT ")) continue
            if (line.startsWith("NOTE")) continue
            if (TIMECODE.matches(line)) continue
            if (CUE_NUMBER.matches(line)) continue
            val cleaned = line.replace(TAG, "").replace(ASS_OVERRIDE, "").trim()
            if (cleaned.isNotEmpty()) out += cleaned
        }
        return out.joinToString("\n")
    }
}
