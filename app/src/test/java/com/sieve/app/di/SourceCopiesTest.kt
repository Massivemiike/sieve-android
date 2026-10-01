package com.sieve.app.di

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SourceCopiesTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var cache: File
    private lateinit var copies: SourceCopies

    @Before fun setUp() {
        cache = tmp.newFolder("cache")
        copies = SourceCopies(cache)
    }

    private fun names() = cache.list().orEmpty().sorted()

    @Test fun `materialize copies the bytes into a tx-src file with the source extension`() = runBlocking {
        val bytes = ByteArray(600_000) { (it % 251).toByte() } // spans several copy chunks
        val path = copies.materialize("clip.mkv") { ByteArrayInputStream(bytes) }

        val f = File(path)
        assertEquals(cache.canonicalFile, f.canonicalFile.parentFile)
        assertTrue(f.name.startsWith("tx-src-") && f.name.endsWith(".mkv"))
        assertArrayEquals(bytes, f.readBytes())
    }

    @Test fun `an odd display name never leaks into the file name`() = runBlocking {
        for (name in listOf("noextension", "x.", "a.b/../c", "clip.sp ace", "clip.waytoolongext")) {
            val f = File(copies.materialize(name) { ByteArrayInputStream(ByteArray(1)) })
            assertEquals(name, cache.canonicalFile, f.canonicalFile.parentFile) // stays a direct child
            assertTrue(name, f.name.endsWith(".mp4"))
        }
    }

    @Test fun `a source that cannot be opened leaves nothing behind`() = runBlocking {
        val failure = runCatching { copies.materialize("a.mp4") { null } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(emptyList<String>(), names())
    }

    @Test fun `a copy that fails midway deletes its partial file`() = runBlocking {
        val broken = object : InputStream() {
            var reads = 0
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (reads++ == 0) { b[off] = 1; return 1 }
                throw IOException("storage went away")
            }
        }
        val failure = runCatching { copies.materialize("a.mp4") { broken } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(emptyList<String>(), names())
    }

    @Test fun `a cancelled copy stops and deletes its partial file`() = runBlocking {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val endless = object : InputStream() {
            var reads = 0
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (reads++ > 0) proceed.await(5, TimeUnit.SECONDS) else started.countDown()
                b[off] = 1
                return 1 // never ends on its own
            }
        }
        var outcome: Throwable? = null
        val job = launch(Dispatchers.Default) {
            outcome = runCatching { copies.materialize("a.mp4") { endless } }.exceptionOrNull()
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        proceed.countDown()
        job.join()

        assertTrue(outcome is CancellationException)
        assertEquals(emptyList<String>(), names())
    }

    @Test fun `release deletes a managed copy once and is then a no-op`() = runBlocking {
        val path = copies.materialize("a.mp4") { ByteArrayInputStream(ByteArray(3)) }
        assertTrue(copies.release(path))
        assertFalse(File(path).exists())
        assertFalse(copies.release(path))
    }

    @Test fun `release refuses anything that is not a tx-src file directly in the cache`() {
        val other = tmp.newFolder("elsewhere")
        val foreign = File(other, "tx-src-1.mp4").apply { writeText("x") }       // right name, wrong directory
        val unprefixed = File(cache, "keep.mp4").apply { writeText("x") }          // right directory, wrong name
        val nested = File(cache, "sub").apply { mkdirs() }.let { File(it, "tx-src-2.mp4").apply { writeText("x") } }
        val traversal = "${cache.path}/../elsewhere/tx-src-1.mp4"                 // resolves outside the cache

        for (p in listOf(foreign.path, unprefixed.path, nested.path, traversal, "tx-src-3.mp4", "")) assertFalse(p, copies.release(p))

        assertTrue(foreign.exists() && unprefixed.exists() && nested.exists())
    }

    @Test fun `sweep deletes unreferenced copies only`() {
        val used = File(cache, "tx-src-1.mp4").apply { writeText("x") }
        val orphanA = File(cache, "tx-src-2.mkv").apply { writeText("x") }
        val orphanB = File(cache, "tx-src-3.mp4").apply { writeText("x") }
        val other = File(cache, "image_cache.bin").apply { writeText("x") }
        val dirLikeCopy = File(cache, "tx-src-dir").apply { mkdirs() }

        val deleted = copies.sweep(setOf(used.path, "/not/in/cache/tx-src-9.mp4"))

        assertEquals(2, deleted)
        assertTrue(used.exists() && other.exists() && dirLikeCopy.exists())
        assertFalse(orphanA.exists() || orphanB.exists())
    }

    @Test fun `sweep matches in-use paths however they are spelled`() {
        val used = File(cache, "tx-src-1.mp4").apply { writeText("x") }
        assertEquals(0, copies.sweep(setOf("${cache.path}/./tx-src-1.mp4")))
        assertTrue(used.exists())
    }
}
