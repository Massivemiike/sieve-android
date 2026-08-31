package com.sieve.storage.sink

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AppFilesSinkInstrumentedTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun writesUnderFilesOutputSieve() = runBlocking {
        val sink = AppFilesSink(ctx)
        val t = sink.write("Music", "a.mp4", "video/mp4", "V".byteInputStream())
        sink.commit(t)
        val f = File(File(File(ctx.filesDir, "output/Sieve"), "Music"), "a.mp4")
        assertTrue(f.exists())
        assertEquals("V", f.readText())
        assertTrue("a.mp4" in sink.existingNames("Music"))
        f.parentFile?.deleteRecursively()
    }
}
