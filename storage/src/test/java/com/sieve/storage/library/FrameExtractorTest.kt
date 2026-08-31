package com.sieve.storage.library

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameExtractorTest {

    @Test fun `builds input-seek single-frame high-quality png args`() {
        assertEquals(
            listOf("-y", "-ss", "12.5", "-i", "/proc/self/fd/10", "-frames:v", "1", "-q:v", "2", "/proc/self/fd/11"),
            FrameExtractorArgs.build("/proc/self/fd/10", "/proc/self/fd/11", 12.5),
        )
    }

    @Test fun `formats whole-second timestamps without trailing decimals noise`() {
        val args = FrameExtractorArgs.build("/proc/self/fd/1", "/proc/self/fd/2", 5.0)
        assertEquals("5", args[args.indexOf("-ss") + 1])
    }

    private fun store(readFd: Int, writeFd: Int, onCreate: (String) -> Unit) = object : DocumentStore {
        override suspend fun listChildren(treeUri: String, parentDocumentId: String?) = emptyList<LibraryEntry>()
        override suspend fun rename(uri: String, newName: String): LibraryEntry? = null
        override suspend fun delete(uri: String) = true
        override suspend fun readText(uri: String, maxBytes: Int) = "" to false
        override suspend fun openReadFd(uri: String) = readFd
        override suspend fun openWriteFd(uri: String) = writeFd
        override suspend fun createChild(parentUri: String, mime: String, name: String): LibraryEntry {
            onCreate(name)
            return LibraryEntry("id", "uri://$name", name, 0, 0, false, "png")
        }
    }

    @Test fun `extract creates png child and execs frame args over fds`() = runTest {
        val execCalls = mutableListOf<List<String>>()
        var created: String? = null
        val fx = FrameExtractor("/lib/ffmpeg", store(10, 11) { created = it }, exec = { execCalls += it; 0 })
        val e = fx.extract("parent", "src", "video.mp4", 12.5)!!
        assertEquals("video_frame_12500.png", e.name)
        assertEquals("video_frame_12500.png", created)
        assertEquals(
            listOf("/lib/ffmpeg", "-y", "-ss", "12.5", "-i", "/proc/self/fd/10", "-frames:v", "1", "-q:v", "2", "/proc/self/fd/11"),
            execCalls.single(),
        )
    }

    @Test fun `extract returns null when ffmpeg fails`() = runTest {
        val fx = FrameExtractor("/lib/ffmpeg", store(10, 11) {}, exec = { 1 })
        assertNull(fx.extract("parent", "src", "video.mp4", 1.0))
    }
}
