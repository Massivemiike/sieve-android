package com.sieve.storage.library

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleExporterTest {
    private class FakeStore(val srt: String) : DocumentStore {
        var createdName: String? = null
        var createdText: String? = null
        override suspend fun listChildren(treeUri: String, parentDocumentId: String?) = emptyList<LibraryEntry>()
        override suspend fun rename(uri: String, newName: String): LibraryEntry? = null
        override suspend fun delete(uri: String) = true
        override suspend fun readText(uri: String, maxBytes: Int) = srt to false
        override suspend fun openReadFd(uri: String) = -1
        override suspend fun createChild(parentUri: String, mime: String, name: String): LibraryEntry {
            createdName = name
            return LibraryEntry("id", "uri://$name", name, 0, 0, false, "txt")
        }
    }

    @Test fun `export writes converted transcript as sibling txt`() = runTest {
        val store = FakeStore("1\n00:00:01,000 --> 00:00:02,000\nHi there")
        val exporter = SubtitleExporter(store) { uri, text -> store.createdText = text }
        val out = exporter.export("parent://dir", "sub://x", "video.en.srt")!!
        assertEquals("video.en.transcript.txt", out.name)
        assertEquals("video.en.transcript.txt", store.createdName)
        assertEquals("Hi there", store.createdText)
    }
}
