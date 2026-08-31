package com.sieve.storage

import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.storage.library.FrameExtractorArgs
import com.sieve.storage.service.JavaWorkDirFs
import com.sieve.storage.service.SafOutputProvider
import com.sieve.storage.service.SinkSelector
import com.sieve.storage.sink.SafTreeSink
import com.sieve.transcode.runner.android.FfmpegBinary
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof of the storage seam on-device. Uses a `DocumentFile.fromFile` temp tree (shares
 * the DocumentFile code path) so it runs without the flaky ACTION_OPEN_DOCUMENT_TREE grant.
 */
@RunWith(AndroidJUnit4::class)
class StorageSmokeInstrumentedTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun job(id: String) =
        QueueJob(id, JobSpec.Download("https://x/$id", emptyList()), OutputRequest("", ""), title = "T")

    private fun execFfmpeg(bin: String, args: List<String>): Int {
        val p = ProcessBuilder(listOf(bin) + args).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText() // drain
        return p.waitFor()
    }

    @Test fun grantTree_writeWorkFile_finalize_readBack() = runBlocking {
        val treeRoot = File(ctx.cacheDir, "smoke-tree-${System.nanoTime()}").apply { mkdirs() }
        val sink = SafTreeSink(ctx, DocumentFile.fromFile(treeRoot))
        val selector = object : SinkSelector {
            override suspend fun select(job: QueueJob) = sink
        }
        val provider = SafOutputProvider(ctx.filesDir.absolutePath, JavaWorkDirFs(), selector)

        val j = job("smoke1")
        val prepared = provider.prepare(j)
        File(prepared.workDir, "video.mp4").writeText("SMOKE-BYTES")
        File(prepared.workDir, "video.en.srt").writeText("1\n00:00:01,000 --> 00:00:02,000\nHi")

        val loc = provider.finalize(j, prepared)
        assertEquals("video.mp4", loc.displayPath.substringAfterLast('/'))
        assertTrue(!File(prepared.workDir).exists(), "work dir not cleaned")
        assertEquals("SMOKE-BYTES", File(treeRoot, "video.mp4").readText())
        assertTrue(File(treeRoot, "video.en.srt").exists())

        treeRoot.deleteRecursively()
        Unit // keep @Test void
    }

    @Test fun frameExtract_fromGeneratedClip() = runBlocking {
        val bin = FfmpegBinary.path(ctx)
        val clip = File(ctx.cacheDir, "gen-${System.nanoTime()}.mp4")
        assertEquals(
            0,
            execFfmpeg(bin, listOf("-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=320x240:rate=15", "-c:v", "libx264", "-pix_fmt", "yuv420p", clip.absolutePath)),
        )
        assertTrue(clip.exists() && clip.length() > 0)

        val outPng = File(ctx.cacheDir, "gen_frame_500.png")
        assertEquals(0, execFfmpeg(bin, FrameExtractorArgs.build(clip.absolutePath, outPng.absolutePath, 0.5)))
        assertTrue(outPng.exists() && outPng.length() > 0, "no frame written")

        clip.delete(); outPng.delete()
        Unit // keep @Test void
    }
}
