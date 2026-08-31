package com.sieve.transcode.output

/**
 * Output filename sanitization + path resolution, ported from the desktop store.
 *
 * The `_<presetId>` suffix is appended **only when the user supplied no output name** — this is
 * what stops a batch of presets over one source from clobbering each other's output. Android
 * hardcodes the path separator to `/` (the desktop's `\\` heuristic is dead here).
 */
object OutputNaming {

    private val ILLEGAL = Regex("""[<>:"/\\|?*]""")
    private val TRAILING_EXT = Regex("""\.[^.]+$""")

    fun sanitizeName(raw: String): String? =
        raw.replace(ILLEGAL, "_").replace(TRAILING_EXT, "").trim().ifEmpty { null }

    fun sanitizeExt(raw: String?): String? =
        raw?.removePrefix(".")?.replace(Regex("[^a-zA-Z0-9]"), "")?.lowercase()?.ifEmpty { null }

    fun resolveOutputPath(
        outDir: String,
        sourceName: String,
        presetId: String,
        presetExt: String,
        outputName: String?,
        outputExt: String?,
        sep: String = "/",
    ): String {
        val base = outputName ?: sourceName.replace(TRAILING_EXT, "")
        val ext = outputExt ?: presetExt
        return if (outputName != null) "$outDir$sep$base.$ext"
        else "$outDir$sep${base}_$presetId.$ext"
    }
}
