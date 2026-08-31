package com.sieve.storage.sink

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeSinkTest {

    private fun stream(s: String) = s.byteInputStream()

    @Test fun `write then existingNames reflects committed and pending`() = runTest {
        val sink = FakeSink()
        val t = sink.write(null, "a.mp4", "video/mp4", stream("data"))
        assertEquals("a.mp4", t.name)
        assertTrue("a.mp4" in sink.existingNames(null))
        sink.commit(t)
        assertEquals("data", sink.committedBytes(null, "a.mp4"))
    }

    @Test fun `deletePending removes a pending target`() = runTest {
        val sink = FakeSink()
        val t = sink.write("Music", "x.mp4", "video/mp4", stream("d"))
        sink.deletePending(t)
        assertTrue("x.mp4" !in sink.existingNames("Music"))
    }

    @Test fun `dir labels are isolated`() = runTest {
        val sink = FakeSink()
        sink.write("A", "same.mp4", "video/mp4", stream("1")).also { sink.commit(it) }
        assertTrue("same.mp4" in sink.existingNames("A"))
        assertTrue("same.mp4" !in sink.existingNames("B"))
    }

    @Test fun `write can be programmed to fail for rollback tests`() = runTest {
        val sink = FakeSink(failOnName = "boom.srt")
        try {
            sink.write(null, "boom.srt", "text/plain", stream("x"))
            assertTrue(false, "expected failure")
        } catch (e: Exception) {
            assertEquals("injected", e.message)
        }
    }
}
