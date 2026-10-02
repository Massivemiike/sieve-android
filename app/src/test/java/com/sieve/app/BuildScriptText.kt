package com.sieve.app

/**
 * Reads of the repository's own Gradle build scripts by the guard tests ([SdkLevelsTest], [NativeLibPackagingTest],
 * [ManifestPermissionsTest]). Pure, so it needs no device and no signing keys.
 */
internal object BuildScriptText {

    /** Removes // and /* */ comments while leaving string literals (which may contain `//`) alone. */
    fun stripComments(src: String): String {
        val out = StringBuilder()
        var i = 0
        var inString = false
        while (i < src.length) {
            val c = src[i]
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i++ } else if (c == '"') inString = false
                }
                c == '"' -> { inString = true; out.append(c) }
                c == '/' && src.startsWith("//", i) -> { while (i < src.length && src[i] != '\n') i++; continue }
                c == '/' && src.startsWith("/*", i) -> {
                    val end = src.indexOf("*/", i + 2)
                    i = if (end < 0) src.length else end + 2
                    out.append(' ')
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
