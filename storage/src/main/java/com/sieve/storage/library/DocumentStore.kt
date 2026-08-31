package com.sieve.storage.library

/** Listing/read/mutate seam over a SAF tree, so the Library logic is testable behind a fake. */
interface DocumentStore {
    suspend fun listChildren(treeUri: String, parentDocumentId: String?): List<LibraryEntry>
    suspend fun rename(uri: String, newName: String): LibraryEntry?
    suspend fun delete(uri: String): Boolean
    suspend fun readText(uri: String, maxBytes: Int = 256 * 1024): Pair<String, Boolean> // text, truncated
    suspend fun openReadFd(uri: String): Int   // /proc/self/fd for ffmpeg
    suspend fun createChild(parentUri: String, mime: String, name: String): LibraryEntry?
}
