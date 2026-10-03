package com.sieve.queue.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * A test-only model of how yt-dlp 2026.08.19 turns the spawn-time `-o` template (plus `--parse-metadata`
 * actions) into a file name: `sanitize_filename` in its default mode (what Android and Windows both run),
 * the `%(field).<n>B` byte cut, the `%(a,b)s` alternative, and the order they run in - the `B` cut FIRST, the
 * sanitizer on the result. That order is the whole bug: the sanitizer swaps `" * : < > ? | / \` for 3-byte
 * full-width look-alikes.
 *
 * `--parse-metadata` runs on VIDEOS only ([render]); the playlist-level files are named from the same `-o`
 * evaluated on the playlist, where the fields it set do not exist ([renderPlaylistInfoJson]).
 *
 * Only what Sieve's templates use is modelled; anything else throws, so a new template feature fails the
 * tests loudly instead of being silently mis-modelled. [FILENAME_VECTORS] pins the model to the real binary.
 */
internal object YtdlpFilenameModel {
    private val timestamp = Regex("[0-9]+(?::[0-9]+)+")
    private val repeatedMarker = Regex("(?s)(\u0000.)(?:(?=\\1)..)+")
    private const val STRIP = "(?:\u0000.|[ _-])*"
    private val edgeMarkers = Regex("(?s)^\u0000.$STRIP|$STRIP\u0000.\\z")
    private val token = Regex("""%\(([\w,]+)\)(?:\.(\d+))?([sBS])""")

    /** yt-dlp `sanitize_filename(s)` with `restricted=False, is_id=NO_DEFAULT`. */
    fun sanitizeFilename(s: String): String {
        if (s.isEmpty()) return ""
        val stamped = timestamp.replace(s) { it.value.replace(':', '_') }
        val sb = StringBuilder(stamped.length)
        stamped.codePoints().forEach { sb.append(replaceInsane(it)) }
        var result = sb.toString()
        result = repeatedMarker.replace(result) { it.groupValues[1] }
        result = edgeMarkers.replace(result, "")
        result = result.replace("\u0000", "")
        return result.ifEmpty { "_" }
    }

    private fun replaceInsane(cp: Int): String = when {
        cp == '\n'.code -> "\u0000 "
        cp == '/'.code -> "⧸"
        cp == '\\'.code -> "⧹"
        cp < 128 && "\"*:<>?|".indexOf(cp.toChar()) >= 0 -> String(Character.toChars(cp + 0xFEE0))
        cp < 32 || cp == 127 -> ""
        else -> String(Character.toChars(cp))
    }

    /** `%(field).<n>B`: the first [n] UTF-8 bytes, a character cut in half is dropped (`decode('utf-8', 'ignore')`). */
    fun cutBytes(value: String, n: Int): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= n) return value
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE)
            .onUnmappableCharacter(CodingErrorAction.IGNORE)
            .decode(ByteBuffer.wrap(bytes, 0, n)).toString()
    }

    /**
     * The file name yt-dlp builds from [args] (`-o`, and every `--parse-metadata FROM:TO` with a single-word
     * TO) for a video called [title]. A field nothing set renders as yt-dlp's `NA`, like the real thing.
     */
    fun render(args: List<String>, title: String, id: String = "dQw4w9WgXcQ", ext: String = "mp4"): String {
        val fields = mutableMapOf("title" to title, "id" to id, "ext" to ext)
        var template: String? = null
        var i = 0
        while (i < args.size - 1) {
            when (args[i]) {
                "-o" -> { template = args[i + 1]; i += 2 }
                "--parse-metadata" -> { applyParseMetadata(args[i + 1], fields); i += 2 }
                else -> i++
            }
        }
        return evaluate(requireNotNull(template) { "no -o in $args" }, fields, sanitize = true)
    }

    /**
     * The name of a PLAYLIST's own `.info.json` (`--write-info-json` on a playlist or channel URL, as the Archive
     * preset does). yt-dlp evaluates the same `-o` on the playlist dict, which has a `title` and an `id` but no `ext`
     * and no field a `--parse-metadata` set (that runs on videos), then swaps the extension for `info.json`.
     */
    fun renderPlaylistInfoJson(args: List<String>, title: String, id: String): String {
        val i = args.indexOf("-o")
        require(i >= 0 && i + 1 < args.size) { "no -o in $args" }
        val name = evaluate(args[i + 1], mapOf("title" to title, "id" to id), sanitize = true)
        return name.substringBeforeLast('.') + ".info.json" // replace_extension: "... [id].NA" -> "... [id].info.json"
    }

    /**
     * `--parse-metadata FROM:%(name)s`. The `%(name)s` spelling, not a bare `name`: engines before 2026 read a bare
     * word as a literal regex and set nothing (every file became `NA [id]`) - the 2025.11.12 inside the APK does.
     */
    private fun applyParseMetadata(spec: String, fields: MutableMap<String, String>) {
        val m = Regex("(?s)(.*?)(?<!\\\\):(.+)$").matchEntire(spec) ?: error("not FROM:TO: $spec")
        val from = m.groupValues[1].replace("\\:", ":")
        val name = Regex("%\\((\\w+)\\)s").matchEntire(m.groupValues[2])?.groupValues?.get(1)
            ?: error("only a TO of the form %(name)s is modelled: ${m.groupValues[2]}")
        val template = if (Regex("[a-zA-Z_]+").matches(from)) "%($from)s" else from
        val data = evaluate(template, fields, sanitize = false)
        // the regex is `(?P<name>.+)` with a plain `search`: the first run of characters up to a newline
        Regex("[^\\n]+").find(data)?.let { fields[name] = it.value }
    }

    private fun evaluate(template: String, fields: Map<String, String>, sanitize: Boolean): String {
        var last = 0
        val sb = StringBuilder()
        for (m in token.findAll(template)) {
            sb.append(template, last, m.range.first)
            last = m.range.last + 1
            val (names, precision, conv) = m.destructured
            // `%(a,b)s` takes the first alternative that is set; yt-dlp: "sanitize and value == ''" counts as unset (-> NA)
            var value = names.split(',').firstNotNullOfOrNull { n -> fields[n]?.takeUnless { it.isEmpty() && sanitize } }
            if (value == null) { sb.append(sanitizeIf(sanitize, "NA")); continue }
            when (conv) {
                "B" -> value = cutBytes(value, precision.toInt())
                "S" -> value = sanitizeFilename(value)
                "s" -> require(precision.isEmpty()) { "%(x).<n>s counts characters; not modelled" }
            }
            sb.append(sanitizeIf(sanitize, value))
        }
        sb.append(template, last, template.length)
        return sb.toString()
    }

    private fun sanitizeIf(on: Boolean, value: String) = if (on) sanitizeFilename(value) else value
}
