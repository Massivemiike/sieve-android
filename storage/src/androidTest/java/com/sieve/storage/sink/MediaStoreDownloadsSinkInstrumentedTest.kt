package com.sieve.storage.sink

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MediaStoreDownloadsSinkInstrumentedTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun insertWriteCommitAppears() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val sink = MediaStoreDownloadsSink(ctx)
        val name = "sieve-test-${System.nanoTime()}.mp4"
        val t = sink.write(null, name, "video/mp4", "DATA".byteInputStream())
        sink.commit(t)
        val bytes = ctx.contentResolver.openInputStream(android.net.Uri.parse(t.uri))!!.use { it.readBytes() }
        assertTrue(bytes.toString(Charsets.UTF_8) == "DATA")
        ctx.contentResolver.delete(android.net.Uri.parse(t.uri), null, null)
        Unit // keep @Test void
    }
}
