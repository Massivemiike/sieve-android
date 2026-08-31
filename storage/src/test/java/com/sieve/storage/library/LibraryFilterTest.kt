package com.sieve.storage.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryFilterTest {
    private fun e(name: String, size: Long = 0, mtime: Long = 0, dir: Boolean = false) =
        LibraryEntry("id-$name", "u-$name", name, size, mtime, dir, com.sieve.storage.naming.MimeMapper.extensionOf(name))

    private val data = listOf(
        e("b.mp4", size = 10, mtime = 100),
        e("a.mp3", size = 30, mtime = 300),
        e("c.jpg", size = 20, mtime = 200),
        e("folder", dir = true),
        e("notes.txt"),
    )

    @Test fun `video filter keeps only video ext (dirs hidden under a kind filter)`() {
        val out = LibraryFilter.apply(data, MediaKind.VIDEO, "", SortKey.NAME, true)
        assertEquals(listOf("b.mp4"), out.map { it.name })
    }

    @Test fun `dirs shown first in ALL`() {
        val out = LibraryFilter.apply(data, MediaKind.ALL, "", SortKey.NAME, true)
        assertEquals("folder", out.first().name)
    }

    @Test fun `search is case-insensitive substring on name`() {
        val out = LibraryFilter.apply(data, MediaKind.ALL, "MP", SortKey.NAME, true)
        assertEquals(setOf("b.mp4", "a.mp3"), out.map { it.name }.toSet())
    }

    @Test fun `sort by size descending`() {
        val out = LibraryFilter.apply(data, MediaKind.AUDIO, "", SortKey.SIZE, false)
        assertEquals(listOf("a.mp3"), out.map { it.name })
    }

    @Test fun `sort by modified ascending`() {
        val out = LibraryFilter.apply(data.filter { !it.isDir }, MediaKind.ALL, "", SortKey.MODIFIED, true)
        assertEquals(listOf("notes.txt", "b.mp4", "c.jpg", "a.mp3"), out.map { it.name })
    }
}
