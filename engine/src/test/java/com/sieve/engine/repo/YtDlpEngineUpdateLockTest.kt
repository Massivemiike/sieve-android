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
import kotlin.test.assertFalse
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
        @Volatile private var execRunning = false
        @Volatile private var killed = false
        /** What execute() reports; the default is a successful empty run. */
        var execExit = 0
        var execOut = "{}"
        var execErr = ""

        override fun version(): String? = "2026.08.19"
        override fun execute(processId: String, url: String, options: List<String>, onProgress: (Float, Long, String) -> Unit): ExecResult {
            log.add("exec-start")
            execRunning = true
            execEntered.countDown()
            if (blockExec) releaseExec.await(10, TimeUnit.SECONDS)
            execRunning = false
            log.add("exec-end")
            // The library throws when its process was destroyed.
            if (killed) throw RuntimeException("Command was canceled")
            return ExecResult(execExit, execOut, execErr)
        }
        // Killing a process ends a hung execute(), like the library's destroyProcessById.
        override fun destroy(processId: String): Boolean {
            log.add("destroy")
            if (execRunning) killed = true
            releaseExec.countDown()
            return true
        }
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

    // ---- analyze: the timeout measures the yt-dlp run, not the wait for an update to finish ----

    private val unsupported = "ERROR: [generic] Unsupported URL: https://example.com/v"

    @Test fun analyzeIsNotChargedForTheTimeItWaitedOnAnUpdate() = runBlocking {
        val client = GatedClient().apply { blockUpdate = true; execExit = 1; execErr = unsupported }
        val engine = YtDlpEngineImpl(client, github, Dispatchers.IO, analyzeTimeoutMs = 150)
        val up = async(Dispatchers.IO) { engine.doUpdate(UpdateChannel.STABLE) }
        assertTrue(client.updateEntered.await(5, TimeUnit.SECONDS))
        val analysis = async(Dispatchers.IO) { engine.analyze("https://example.com/v", null) }
        Thread.sleep(500) // well past the timeout, and still queued behind the update
        client.releaseUpdate.countDown()
        val outcome = withTimeout(5_000) { up.await(); analysis.await() }
        // The real error, not "Timed out while reading this link".
        assertTrue(outcome is AnalyzeOutcome.Failure)
        assertFalse((outcome as AnalyzeOutcome.Failure).message.startsWith("Timed out"), outcome.message)
        assertEquals(listOf("update-start", "update-end", "exec-start", "exec-end"), client.log.toList())
    }

    @Test fun aHungAnalyzeThatStartedAfterTheUpdateIsStillKilled() = runBlocking {
        val client = GatedClient().apply { blockUpdate = true; blockExec = true }
        val engine = YtDlpEngineImpl(client, github, Dispatchers.IO, analyzeTimeoutMs = 150)
        val up = async(Dispatchers.IO) { engine.doUpdate(UpdateChannel.STABLE) }
        assertTrue(client.updateEntered.await(5, TimeUnit.SECONDS))
        val analysis = async(Dispatchers.IO) { engine.analyze("https://example.com/v", null) }
        Thread.sleep(500)
        client.releaseUpdate.countDown()
        val outcome = withTimeout(5_000) { up.await(); analysis.await() }
        assertTrue(outcome is AnalyzeOutcome.Failure && outcome.message.startsWith("Timed out"))
        // The watchdog only started once yt-dlp did: the kill comes after exec-start, never during the wait.
        assertEquals(listOf("update-start", "update-end", "exec-start", "destroy", "exec-end"), client.log.toList())
    }
}
