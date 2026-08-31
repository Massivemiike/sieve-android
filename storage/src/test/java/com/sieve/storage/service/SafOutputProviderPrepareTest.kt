package com.sieve.storage.service

import com.sieve.queue.core.PreparedOutput
import com.sieve.queue.core.QueueJob
import com.sieve.storage.sink.DestinationSink
import com.sieve.storage.sink.FakeSink
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SafOutputProviderPrepareTest {

    private val selector = object : SinkSelector {
        override suspend fun select(job: QueueJob): DestinationSink = FakeSink()
    }

    private fun job(id: String, template: String? = null) =
        QueueJobFixtures.download(id = id, outputTemplate = template ?: "")

    @Test fun `prepare creates the work dir and returns the template`() = runTest {
        val fs = FakeWorkDirFs()
        val p = SafOutputProvider("/files", fs, selector)
        val out = p.prepare(job("j1"))
        assertEquals("/files/work/j1", out.workDir)
        assertEquals("%(title)s [%(id)s].%(ext)s", out.workFileTemplate)
        assertTrue(fs.exists("/files/work/j1"))
    }

    @Test fun `prepare honors a custom template`() = runTest {
        val fs = FakeWorkDirFs()
        val p = SafOutputProvider("/files", fs, selector)
        val out = p.prepare(job("j2", template = "%(id)s.%(ext)s"))
        assertEquals("%(id)s.%(ext)s", out.workFileTemplate)
    }

    @Test fun `prepare is idempotent and never wipes an existing resume dir`() = runTest {
        val fs = FakeWorkDirFs()
        fs.putFile("/files/work/j3", "partial.mp4.part", byteArrayOf(1, 2, 3))
        val p = SafOutputProvider("/files", fs, selector)
        p.prepare(job("j3"))
        assertTrue("partial.mp4.part" in fs.listLeafNames("/files/work/j3"))
        assertEquals(0, fs.deleteCalls)
    }

    @Test fun `discard deletes the work dir`() = runTest {
        val fs = FakeWorkDirFs()
        val p = SafOutputProvider("/files", fs, selector)
        val prepared = p.prepare(job("j4"))
        p.discard(job("j4"), prepared)
        assertEquals(1, fs.deleteCalls)
        assertTrue(!fs.exists("/files/work/j4"))
    }

    @Test fun `discard tolerates an already-missing dir`() = runTest {
        val fs = FakeWorkDirFs()
        val p = SafOutputProvider("/files", fs, selector)
        p.discard(job("gone"), PreparedOutput("/files/work/gone", "t"))
    }
}
