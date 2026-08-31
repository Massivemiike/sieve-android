package com.sieve.storage.library

enum class SortKey { NAME, SIZE, MODIFIED }
enum class MediaKind { ALL, VIDEO, AUDIO, IMAGE }

object LibraryFilter {
    private val VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "flv", "ts", "m4v")
    private val AUDIO = setOf("m4a", "mp3", "opus", "ogg", "flac", "wav", "aac")
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun apply(
        entries: List<LibraryEntry>,
        kind: MediaKind,
        query: String,
        sortKey: SortKey,
        ascending: Boolean,
    ): List<LibraryEntry> {
        val q = query.trim().lowercase()
        val filtered = entries.filter { e ->
            val matchesQuery = q.isEmpty() || e.name.lowercase().contains(q)
            if (e.isDir) return@filter kind == MediaKind.ALL && matchesQuery // dirs are navigation: only in ALL
            val kindOk = when (kind) {
                MediaKind.ALL -> true
                MediaKind.VIDEO -> e.ext in VIDEO
                MediaKind.AUDIO -> e.ext in AUDIO
                MediaKind.IMAGE -> e.ext in IMAGE
            }
            kindOk && matchesQuery
        }
        val cmp: Comparator<LibraryEntry> = when (sortKey) {
            SortKey.NAME -> compareBy { it.name.lowercase() }
            SortKey.SIZE -> compareBy { it.size }
            SortKey.MODIFIED -> compareBy { it.lastModified }
        }
        val ordered = filtered.sortedWith(if (ascending) cmp else cmp.reversed())
        return ordered.sortedByDescending { it.isDir } // directories always float to the top
    }
}
