package com.sieve.engine.repo

import com.sieve.engine.update.GithubReleaseApi
import com.sieve.engine.update.UpdateChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * yt-dlp updates replace the yt-dlp files in place, so an update and a yt-dlp run must never
 * overlap. Real threads: the client calls block, like the library's.
 */
class YtDlpEngineUpdateLockTest {
    private class GatedClient : YoutubeDLClient {
        val log = CopyOnWriteArrayList<String>()
        val execEntered = CountDownLatch(1)
        val releaseExec = CountDownLatch(1)
        val updateEntered = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        var blockExec = false
        var blockUpdate = false

        override fun version(): String? = "2026.08.19"
        override fun execute(processId: String, url: String, options: List<String>, onProgress: (Float, Long, String) -> Unit): ExecResult {
            log.add("exec-start")
            execEntered.countDown()
            if (blockExec) releaseExec.await(10, TimeUnit.SECONDS)
            log.add("exec-end")
            return ExecResult(0, "{}", "")
        }
        override fun destroy(processId: String): Boolean = true
        override fun update(nightly: Boolean): String {
            log.add("update-start")
            updateEntered.countDown()
            if (blockUpdate) releaseUpdate.await(10, TimeUnit.SECONDS)
            log.add("update-end")
            return "DONE"
        }
    }

    private val github = object : GithubReleaseApi { override suspend fun latestTag(): String? = null }

    @Test fun updateWaitsForARunningDownload() = runBlocking {
        val client = GatedClient().apply { blockExec = true }
        val engine = YtDlpEngineImpl(client, github, Dispatchers.IO)
        val dl = async(Dispatchers.IO) { engine.download("d1", "https://example.com/v", listOf("-f", "b")).toList() }
        assertTrue(client.execEntered.await(5, TimeUnit.SECONDS))
        val up = async(Dispatchers.IO) { engine.doUpdate(UpdateChannel.STABLE) }
        assertTrue(!client.updateEntered.await(300, TimeUnit.MILLISECONDS), "update started under a running yt-dlp")
        client.releaseExec.countDown()
        withTimeout(5_000) { dl.await(); up.await() }
        assertEquals(listOf("exec-start", "exec-end", "update-start", "update-end"), client.log.toList())
    }

    @Test fun downloadWaitsForARunningUpdate() = runBlocking {
        val client = GatedClient().apply { blockUpdate = true }
        val engine = YtDlpEngineImpl(client, github, Dispatchers.IO)
        val up = async(Dispatchers.IO) { engine.doUpdate(UpdateChannel.STABLE) }
        assertTrue(client.updateEntered.await(5, TimeUnit.SECONDS))
        val dl = async(Dispatchers.IO) { engine.download("d1", "https://example.com/v", listOf("-f", "b")).toList() }
        assertTrue(!client.execEntered.await(300, TimeUnit.MILLISECONDS), "yt-dlp started during an update")
        client.releaseUpdate.countDown()
        val events = withTimeout(5_000) { up.await(); dl.await() }
        assertEquals(listOf("update-start", "update-end", "exec-start", "exec-end"), client.log.toList())
        assertEquals(EngineEvent.Completed(0), events.last())
    }

    @Test fun downloadCancelledWhileWaitingForAnUpdateNeverStarts() = runBlocking {
        val client = GatedClient().apply { blockUpdate = true }
        val engine = YtDlpEngineImpl(client, github, Dispatchers.IO)
        val up = async(Dispatchers.IO) { engine.doUpdate(UpdateChannel.STABLE) }
        assertTrue(client.updateEntered.await(5, TimeUnit.SECONDS))
        val dl = async(Dispatchers.IO) { engine.download("d1", "https://example.com/v", listOf("-f", "b")).toList() }
        Thread.sleep(200)
        engine.cancel("d1")
        client.releaseUpdate.countDown()
        val events = withTimeout(5_000) { up.await(); dl.await() }
        assertTrue("exec-start" !in client.log, "a cancelled download still ran yt-dlp")
        assertEquals(EngineEvent.Cancelled, events.last())
    }
}
