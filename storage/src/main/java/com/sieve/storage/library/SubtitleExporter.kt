package com.sieve.storage.library

/**
 * Reads a subtitle via [DocumentStore], converts it to plain text, and writes a sibling
 * `<name>.transcript.txt`. [writeText] is injected (real impl = ContentResolver output stream in
 * `:app`) so this is fully JVM-testable.
 */
class SubtitleExporter(
    private val store: DocumentStore,
    private val writeText: suspend (uri: String, text: String) -> Unit = { _, _ -> },
) {
    suspend fun export(parentUri: String, subtitleUri: String, subtitleName: String): LibraryEntry? {
        val (raw, _) = store.readText(subtitleUri)
        val text = SrtToPlainText.convert(raw)
        val stem = subtitleName.substringBeforeLast('.', subtitleName)
        val outName = "$stem.transcript.txt"
        val entry = store.createChild(parentUri, "text/plain", outName) ?: return null
        writeText(entry.uri, text)
        return entry.copy(name = outName)
    }
}
