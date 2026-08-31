package com.sieve.storage.naming

object CollisionResolver {

    private data class Parts(val stem: String, val ext: String)

    /**
     * Split into (stem, ext). A leading-dot name like ".env" is treated as an extensionless stem
     * so it never becomes " (1).env".
     */
    private fun split(name: String): Parts {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) Parts(name.substring(0, dot), name.substring(dot))
        else Parts(name, "")
    }

    private fun withIndex(p: Parts, n: Int): String =
        if (n == 0) p.stem + p.ext else "${p.stem} ($n)${p.ext}"

    fun resolve(desired: String, existing: Set<String>): String {
        val p = split(desired)
        var n = 0
        while (true) {
            val candidate = withIndex(p, n)
            if (candidate !in existing) return candidate
            n++
        }
    }

    /**
     * Applies ONE shared numeric suffix so a sidecar set stays grouped. The suffix goes after the
     * SHARED base (longest common prefix trimmed to a dot boundary), so a compound sidecar extension
     * like `.en.srt` keeps its whole extension: `foo.en.srt` → `foo (1).en.srt`, not `foo.en (1).srt`.
     */
    fun resolveGroup(members: List<String>, existing: Set<String>): List<String> {
        if (members.isEmpty()) return members
        var lcp = members[0]
        for (m in members.drop(1)) lcp = lcp.commonPrefixWith(m)
        val lastDot = lcp.lastIndexOf('.')
        val base = if (lastDot > 0) lcp.substring(0, lastDot) else lcp
        val exts = members.map { it.substring(base.length) }
        var n = 0
        while (true) {
            val candidates = members.indices.map { i ->
                if (n == 0) base + exts[i] else "$base ($n)${exts[i]}"
            }
            if (candidates.none { it in existing }) return candidates
            n++
        }
    }
}
