package com.sieve.app.settings

import java.io.File
import java.io.IOException
import java.io.InputStream

/** What Settings shows for the imported cookies.txt. [modifiedAt] is the file's mtime (epoch ms). */
data class CookiesInfo(val count: Int, val modifiedAt: Long) {
    fun ageDays(now: Long): Double = ((now - modifiedAt) / DAY_MS.toDouble()).coerceAtLeast(0.0)

    companion object { const val DAY_MS = 86_400_000L }
}

/** Same thresholds as the desktop Settings chip: older than 30 days may be invalid, 90+ almost certainly is. */
enum class CookieAge { FRESH, STALE, VERY_STALE;
    companion object {
        fun of(ageDays: Double) = when {
            ageDays > 90 -> VERY_STALE
            ageDays > 30 -> STALE
            else -> FRESH
        }
    }
}

/** Pure cookies.txt (Netscape format) handling. */
object CookiesFile {
    const val HEADER = "# Netscape HTTP Cookie File"
    private const val HTTP_ONLY = "#HttpOnly_"
    private const val FIELDS = 7
    private val MAGIC = Regex("#( Netscape)? HTTP Cookie File")

    class Cleaned(val text: String, val count: Int)

    /**
     * A copy yt-dlp will accept, plus how many cookies it holds. yt-dlp aborts on a file with no
     * `# Netscape HTTP Cookie File` first line or with any malformed line, and a BOM / CRLF export is
     * common on Android, so this prepends the header when it is missing, normalises newlines, and drops
     * blank and malformed lines. A cookie line is 7 tab-separated fields (an empty value keeps its tab),
     * optionally behind `#HttpOnly_`; other `#` lines are comments and are kept.
     */
    fun clean(raw: String): Cleaned {
        val lines = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val kept = ArrayList<String>(lines.size)
        var count = 0
        for (line in lines) {
            if (line.isBlank()) continue
            val isComment = line.startsWith("#") && !line.startsWith(HTTP_ONLY)
            if (isComment) { kept += line; continue }
            val fields = line.removePrefix(HTTP_ONLY).split('\t')
            if (fields.size == FIELDS && fields[0].isNotBlank()) { kept += line; count++ }
        }
        if (kept.isEmpty() || !MAGIC.containsMatchIn(kept.first())) kept.add(0, HEADER)
        return Cleaned(kept.joinToString("\n", postfix = "\n"), count)
    }

    /** "< 1 day old" / "1 day old" / "N days old", with the stale suffix the desktop chip uses. */
    fun ageLabel(ageDays: Double): String {
        val base = when {
            ageDays < 1 -> "< 1 day old"
            ageDays < 2 -> "1 day old"
            else -> "${ageDays.toInt()} days old"
        }
        return when (CookieAge.of(ageDays)) {
            CookieAge.VERY_STALE -> "$base · very stale"
            CookieAge.STALE -> "$base · stale"
            CookieAge.FRESH -> base
        }
    }

    fun warning(ageDays: Double): String? = when (CookieAge.of(ageDays)) {
        CookieAge.VERY_STALE -> "Very old — almost certainly expired. Re-export from your browser."
        CookieAge.STALE -> "Over 30 days old — may have expired. Re-export from your browser."
        CookieAge.FRESH -> null
    }

    fun statusLine(info: CookiesInfo) =
        "cookies.txt loaded (${info.count} ${if (info.count == 1) "cookie" else "cookies"})"
}

/**
 * The app-private copy of the user's cookies.txt. yt-dlp needs a real path (a content:// Uri from the
 * picker is no use to a native process), so an import copies the picked file to `<dir>/cookies.txt`
 * and Settings stores THAT absolute path. IO is injected so this is plain-JVM testable.
 *
 * The file is read-only to yt-dlp in practice: yt-dlp rewrites the cookie file it is given, so every run
 * (download or analyze) is handed a private per-run copy by `YtDlpEngineImpl`. That keeps this file's
 * modified time at the import date the age chip reads, and keeps concurrent runs apart.
 */
class CookiesStore(
    private val dir: File,
    /** Opens the picked document (a content Uri string); null when it can't be read. */
    private val open: (String) -> InputStream?,
    /** The picked document's own last-modified time, so the age warning reflects when it was exported. */
    private val lastModifiedOf: (String) -> Long? = { null },
) {
    val file: File get() = File(dir, FILE_NAME)

    sealed interface Result {
        data class Imported(val info: CookiesInfo) : Result
        /** Readable, but no cookie lines in it (a JSON export, the wrong file, ...). */
        data object NotCookies : Result
        data object Unreadable : Result
    }

    fun importFrom(uri: String): Result {
        val raw = try {
            open(uri)?.use { readCapped(it) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return Result.Unreadable
        val cleaned = CookiesFile.clean(raw)
        if (cleaned.count == 0) return Result.NotCookies

        return try {
            dir.mkdirs()
            // Write beside, then replace: a failed copy must never clobber a working file.
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(cleaned.text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) { tmp.delete(); return Result.Unreadable }
            }
            lastModifiedOf(uri)?.takeIf { it > 0 }?.let { file.setLastModified(it) }
            Result.Imported(CookiesInfo(cleaned.count, file.lastModified()))
        } catch (_: IOException) {
            Result.Unreadable
        }
    }

    /** The loaded cookies, or null when there is no (readable) file. */
    fun info(): CookiesInfo? {
        val f = file
        if (!f.isFile) return null
        val count = try { CookiesFile.clean(f.readText(Charsets.UTF_8)).count } catch (_: IOException) { return null }
        return if (count > 0) CookiesInfo(count, f.lastModified()) else null
    }

    fun remove() {
        file.delete()
        File(dir, "$FILE_NAME.tmp").delete()
    }

    /** Reads at most [MAX_BYTES]; a bigger file is not a cookies.txt (it is treated as unreadable by the caller). */
    private fun readCapped(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val FILE_NAME = "cookies.txt"
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
