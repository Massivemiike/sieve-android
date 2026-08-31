package com.sieve.storage.sink

import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SafTreeSinkInstrumentedTest {

    private lateinit var root: File
    private lateinit var sink: SafTreeSink

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        root = File(ctx.cacheDir, "tree-${System.nanoTime()}").apply { mkdirs() }
        sink = SafTreeSink(ctx, DocumentFile.fromFile(root))
    }

    @After fun teardown() { root.deleteRecursively() }

    @Test fun writeCommitReadBack() = runBlocking {
        val t = sink.write(null, "clip.mp4", "video/mp4", "HELLO".byteInputStream())
        sink.commit(t)
        val f = File(root, "clip.mp4")
        assertTrue(f.exists())
        assertEquals("HELLO", f.readText())
        assertTrue("clip.mp4" in sink.existingNames(null))
    }

    @Test fun overwriteTruncates() = runBlocking {
        sink.commit(sink.write(null, "a.txt", "text/plain", "LONG-CONTENT".byteInputStream()))
        sink.commit(sink.write(null, "a.txt", "text/plain", "short".byteInputStream()))
        assertEquals("short", File(root, "a.txt").readText())
    }

    @Test fun subfolderLabelCreatesDir() = runBlocking {
        val t = sink.write("Music", "s.m4a", "audio/mp4", "AUDIO".byteInputStream())
        sink.commit(t)
        assertTrue(File(File(root, "Music"), "s.m4a").exists())
        assertEquals("s.m4a", t.name)
    }

    @Test fun deletePendingRemovesFile() = runBlocking {
        val t = sink.write(null, "gone.mp4", "video/mp4", "X".byteInputStream())
        sink.deletePending(t)
        assertTrue(!File(root, "gone.mp4").exists())
    }
}
