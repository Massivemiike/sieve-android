package com.sieve.storage.service

import com.sieve.queue.core.PreparedOutput
import com.sieve.queue.core.QueueJob
import com.sieve.storage.sink.DestinationSink
import com.sieve.storage.sink.FakeSink
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SafOutputProviderFinalizeTest {

    private fun providerWith(fs: FakeWorkDirFs, sink: DestinationSink): SafOutputProvider {
        val selector = object : SinkSelector {
            override suspend fun select(job: QueueJob) = sink
        }
        return SafOutputProvider("/files", fs, selector)
    }

    private fun seed(fs: FakeWorkDirFs, id: String, vararg leaves: Pair<String, String>) {
        for ((leaf, body) in leaves) fs.putFile("/files/work/$id", leaf, body.toByteArray())
    }

    @Test fun `copies primary plus sidecars, commits, and deletes work dir`() = runTest {
        val fs = FakeWorkDirFs()
        seed(
            fs, "j1",
            "video.mp4" to "MP4",
            "video.en.srt" to "SUBS",
            "video.info.json" to "{}",
            "video.mp4.part" to "SCRATCH",
        )
        val sink = FakeSink()
        val p = providerWith(fs, sink)
        val loc = p.finalize(QueueJobFixtures.download("j1"), PreparedOutput("/files/work/j1", "t"))

        assertEquals("video.mp4", loc.displayPath.substringAfterLast('/'))
        assertTrue(loc.uri!!.endsWith("/video.mp4"))
        assertEquals("MP4", sink.committedBytes(null, "video.mp4"))
        assertEquals("SUBS", sink.committedBytes(null, "video.en.srt"))
        assertEquals("{}", sink.committedBytes(null, "video.info.json"))
        assertEquals(null, sink.committedBytes(null, "video.mp4.part"))
        assertEquals(1, fs.deleteCalls)
    }

    @Test fun `applies grouped collision suffix to the whole set`() = runTest {
        val fs = FakeWorkDirFs()
        seed(fs, "j2", "foo.mp4" to "V", "foo.en.srt" to "S")
        val sink = FakeSink()
        sink.write(null, "foo.mp4", "video/mp4", "OLD".byteInputStream()).also { sink.commit(it) }

        val p = providerWith(fs, sink)
        val loc = p.finalize(QueueJobFixtures.download("j2"), PreparedOutput("/files/work/j2", "t"))

        assertEquals("foo (1).mp4", loc.displayPath.substringAfterLast('/'))
        assertEquals("V", sink.committedBytes(null, "foo (1).mp4"))
        assertEquals("S", sink.committedBytes(null, "foo (1).en.srt"))
    }

    @Test fun `rolls back copied files and preserves work dir when a copy fails`() = runTest {
        val fs = FakeWorkDirFs()
        seed(fs, "j3", "video.mp4" to "V", "video.en.srt" to "S")
        val sink = FakeSink(failOnName = "video.en.srt")
        val p = providerWith(fs, sink)

        assertFailsWith<RuntimeException> {
            p.finalize(QueueJobFixtures.download("j3"), PreparedOutput("/files/work/j3", "t"))
        }
        assertTrue("video.mp4" !in sink.existingNames(null))
        assertEquals(0, fs.deleteCalls)
    }

    @Test fun `no real outputs throws and leaves the work dir intact`() = runTest {
        val fs = FakeWorkDirFs()
        seed(fs, "j4", "only.part" to "X")
        val sink = FakeSink()
        val p = providerWith(fs, sink)
        assertFailsWith<IllegalStateException> {
            p.finalize(QueueJobFixtures.download("j4"), PreparedOutput("/files/work/j4", "t"))
        }
        assertEquals(0, fs.deleteCalls)
    }

    @Test fun `routes to the dir label the job requested`() = runTest {
        val fs = FakeWorkDirFs()
        seed(fs, "j5", "a.mp4" to "V")
        val sink = FakeSink()
        val p = providerWith(fs, sink)
        val job = QueueJobFixtures.download("j5", outputDirLabel = "Music")
        val loc = p.finalize(job, PreparedOutput("/files/work/j5", "t"))
        assertEquals("Music/a.mp4", loc.displayPath)
        assertEquals("V", sink.committedBytes("Music", "a.mp4"))
    }
}
