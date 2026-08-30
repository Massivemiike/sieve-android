package com.sieve.engine.model

/**
 * Display helpers ported from the desktop. NB: `bytes` (B..TB) and `size`
 * (KB..GB, "?" on falsy) are deliberately distinct; both are base-10 despite
 * MiB/KiB speed units.
 */
object Format {
    fun duration(sec: Double?): String {
        if (sec == null || sec <= 0) return ""
        val total = sec.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun bytes(b: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1000 && i < units.size - 1) { v /= 1000; i++ }
        return if (i == 0) "$b ${units[0]}" else "%.1f %s".format(java.util.Locale.ROOT, v, units[i])
    }

    fun size(b: Long?): String {
        if (b == null || b <= 0) return "?"
        val units = listOf("KB", "MB", "GB")
        var v = b.toDouble() / 1000
        var i = 0
        while (v >= 1000 && i < units.size - 1) { v /= 1000; i++ }
        return "%.1f %s".format(java.util.Locale.ROOT, v, units[i])
    }

    fun speedMiB(speed: String?): Double {
        if (speed == null) return 0.0
        val m = Regex("([\\d.]+)\\s*(GiB|MiB|KiB)/s", RegexOption.IGNORE_CASE).find(speed) ?: return 0.0
        val n = m.groupValues[1].toDoubleOrNull() ?: return 0.0
        return when (m.groupValues[2].uppercase()) {
            "GIB" -> n * 1024
            "MIB" -> n
            "KIB" -> n / 1024
            else -> 0.0
        }
    }
}
