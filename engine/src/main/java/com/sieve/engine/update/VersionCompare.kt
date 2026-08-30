package com.sieve.engine.update

/** yt-dlp tags are YYYY.MM.DD[.rev]; component-wise numeric compare, unparsable → no update. */
object VersionCompare {
    fun isNewer(latest: String?, current: String?): Boolean {
        if (latest == null || current == null) return false
        val l = parse(latest)
        val c = parse(current)
        if (l.isEmpty() || c.isEmpty()) return false
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parse(v: String): List<Int> {
        val nums = v.split(".").map { it.toIntOrNull() }
        return if (nums.any { it == null }) emptyList() else nums.filterNotNull()
    }
}
