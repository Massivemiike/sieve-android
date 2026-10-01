package com.sieve.storage.service

import com.sieve.queue.core.PreparedOutput
import com.sieve.queue.core.QueueJob
import com.sieve.storage.sink.DestinationSink
import com.sieve.storage.sink.FakeSink
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A failed yt-dlp run (one bad playlist entry exits non-zero after the others were saved) must keep
 * what finished: salvage publishes finished media to the destination before the work dir is wiped.
 */
class SafOutputProviderSalvageTest {

    private fun providerWith(fs: FakeWorkDirFs, sink: DestinationSink): SafOutputProvider {
        val selector = object : SinkSelector {
            override suspend fun select(job: QueueJob) = sink
        }
        return SafOutputProvider("/files", fs, selector)
    }

    private fun seed(fs: FakeWorkDirFs, id: String, vararg leaves: Pair<String, String>) {
        for ((leaf, body) in leaves) fs.putFile("/files/work/$id", leaf, body.toByteArray())
    }

    private fun prepared(id: String) = PreparedOutput("/files/work/$id", "t")

    @Test fun `copies the finished entries of a failed playlist and clears the work dir`() = runTest {
        val fs = FakeWorkDirFs()
        seed(
            fs, "p1",
            "One [a1].mp4" to "ONE",
            "Two [b2].mp4" to "TWO",
            "Two [b2].info.json" to "{}",
            "Three [c3].mp4.part" to "HALF",
            "Three [c3].f137.mp4" to "VIDEO-ONLY",
        )
        val sink = FakeSink()
        val loc = providerWith(fs, sink).salvage(QueueJobFixtures.download("p1"), prepared("p1"))

        assertNotNull(loc)
        assertEquals("ONE", sink.committedBytes(null, "One [a1].mp4"))
        assertEquals("TWO", sink.committedBytes(null, "Two [b2].mp4"))
        assertEquals("{}", sink.committedBytes(null, "Two [b2].info.json"))
        assertNull(sink.committedBytes(null, "Three [c3].mp4.part"))
        assertNull(sink.committedBytes(null, "Three [c3].f137.mp4"))
        assertTrue(!fs.exists("/files/work/p1"))
    }

    @Test fun `a run that only left scratch salvages nothing and leaves the work dir to the caller`() = runTest {
        val fs = FakeWorkDirFs()
        seed(fs, "p2", "Clip.mp4.part" to "HALF", "Clip.mp4.ytdl" to "{}", "Clip.f251.webm" to "AUDIO")
        val sink = FakeSink()

        assertNull(providerWith(fs, sink).salvage(QueueJobFixtures.download("p2"), prepared("p2")))
        assertTrue(sink.calls.none { it.startsWith("write") })
        assertEquals(0, fs.deleteCalls)
    }

    @Test fun `a lone thumbnail, subtitle or info json is not an output`() = runTest {
        val fs = FakeWorkDirFs()
        seed(
            fs, "p3",
            "Clip.jpg" to "THUMB", "Clip.webp" to "THUMB", "Clip.en.vtt" to "SUBS", "Clip.info.json" to "{}",
            "Clip.mp4.part" to "HALF",
        )
        val sink = FakeSink()

        assertNull(providerWith(fs, sink).salvage(QueueJobFixtures.download("p3"), prepared("p3")))
        assertTrue(sink.calls.none { it.startsWith("write") }, "nothing may be published")
        assertEquals(0, fs.deleteCalls)
    }

    @Test fun `an audio-only download counts as media`() = runTest {
        val fs = FakeWorkDirFs()
        seed(fs, "p4", "Song.opus" to "OPUS", "Song.jpg" to "COVER")
        val sink = FakeSink()

        assertNotNull(providerWith(fs, sink).salvage(QueueJobFixtures.download("p4"), prepared("p4")))
        assertEquals("OPUS", sink.committedBytes(null, "Song.opus"))
    }

    @Test fun `a copy that fails rolls back and keeps the work dir so nothing finished is lost`() = runTest {
        val fs = FakeWorkDirFs()
        seed(fs, "p5", "One.mp4" to "ONE", "Two.mp4" to "TWO")
        val sink = FakeSink(failOnName = "Two.mp4")

        assertFailsWith<RuntimeException> {
            providerWith(fs, sink).salvage(QueueJobFixtures.download("p5"), prepared("p5"))
        }
        assertTrue("One.mp4" !in sink.existingNames(null))
        assertTrue(fs.exists("/files/work/p5"))
        assertEquals(0, fs.deleteCalls)
    }

    @Test fun `prepare puts the download archive beside the work dir, not in it`() = runTest {
        val fs = FakeWorkDirFs()
        val out = providerWith(fs, FakeSink()).prepare(QueueJobFixtures.download("p6"))

        assertEquals("/files/work/p6.archive.txt", out.archivePath)
        assertTrue(!out.archivePath!!.startsWith(out.workDir + "/"), "a salvage wipes the work dir; the archive must outlive it")
    }

    @Test fun `discard keeps the archive so a Retry still skips the saved entries`() = runTest {
        val fs = FakeWorkDirFs()
        val p = providerWith(fs, FakeSink())
        fs.putFile("/files/work", "p7.archive.txt", "youtube a1\n".toByteArray())
        seed(fs, "p7", "Clip.mp4.part" to "HALF")

        p.discard(QueueJobFixtures.download("p7"), p.prepare(QueueJobFixtures.download("p7")))

        assertTrue(!fs.exists("/files/work/p7"))
        assertTrue(fs.exists("/files/work/p7.archive.txt"))
    }

    @Test fun `cleanup drops the archive together with the work dir`() = runTest {
        val fs = FakeWorkDirFs()
        val p = providerWith(fs, FakeSink())
        fs.putFile("/files/work", "p8.archive.txt", "youtube a1\n".toByteArray())
        seed(fs, "p8", "Clip.mp4.part" to "HALF")

        p.cleanup(QueueJobFixtures.download("p8"))

        assertTrue(!fs.exists("/files/work/p8"))
        assertTrue(!fs.exists("/files/work/p8.archive.txt"))
    }
}
