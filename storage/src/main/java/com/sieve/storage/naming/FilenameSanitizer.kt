package com.sieve.storage.naming

object FilenameSanitizer {

    private val ILLEGAL = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    private val RESERVED = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) { add("COM$i"); add("LPT$i") }
    }

    private val COLLAPSE = Regex("_+")

    fun sanitize(name: String, maxBytes: Int = 240): String {
        // 1. Replace illegal + control chars, then collapse consecutive replacements to a single '_'.
        val cleaned = buildString(name.length) {
            for (ch in name) {
                append(if (ch in ILLEGAL || ch.code < 0x20 || ch.code == 0x7F) '_' else ch)
            }
        }.replace(COLLAPSE, "_")
        // 2. Split stem/ext on the LAST dot — but ONLY if the trailing ".xyz" looks like a real
        //    extension (alnum, <=8). Otherwise a leading-dot name like "..x" would lose a dot.
        val dot = cleaned.lastIndexOf('.')
        val extCandidate = if (dot > 0) cleaned.substring(dot) else ""
        val hasExt = extCandidate.length in 2..9 && extCandidate.drop(1).all { it.isLetterOrDigit() }
        var stem = if (hasExt) cleaned.substring(0, dot) else cleaned
        var ext = if (hasExt) extCandidate else ""

        // 3. Trim trailing dots/spaces on the stem (FAT rejects these).
        stem = stem.trimEnd('.', ' ')
        ext = ext.trimEnd('.', ' ')
        if (ext == ".") ext = ""

        // 4. Reserved-stem guard (case-insensitive).
        if (stem.uppercase() in RESERVED) stem = "_$stem"

        if (stem.isEmpty() && ext.isEmpty()) return "_"

        // 5. Byte-length cap. Preserve extension; trim stem by whole codepoints.
        var candidate = stem + ext
        if (candidate.toByteArray(Charsets.UTF_8).size <= maxBytes) return candidate

        val extBytes = ext.toByteArray(Charsets.UTF_8).size
        val stemBudget = (maxBytes - extBytes).coerceAtLeast(1)
        val trimmedStem = trimToBytes(stem, stemBudget)
        candidate = if (trimmedStem.isEmpty()) trimToBytes(ext.ifEmpty { "_" }, maxBytes)
        else trimmedStem + ext

        if (candidate.toByteArray(Charsets.UTF_8).size > maxBytes) {
            candidate = trimToBytes(candidate, maxBytes)
        }
        return candidate.ifEmpty { "_" }
    }

    /** Truncate [s] so its UTF-8 encoding is <= [maxBytes], never splitting a codepoint. */
    private fun trimToBytes(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val charCount = Character.charCount(cp)
            val piece = s.substring(i, i + charCount)
            val bytes = piece.toByteArray(Charsets.UTF_8).size
            if (used + bytes > maxBytes) break
            sb.append(piece)
            used += bytes
            i += charCount
        }
        return sb.toString()
    }
}
