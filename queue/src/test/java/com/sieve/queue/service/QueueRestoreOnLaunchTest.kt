package com.sieve.queue.service

import android.content.Intent
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.InMemoryRestoreHoldStore
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueuePersistence
import com.sieve.queue.core.QueueState
import com.sieve.queue.core.RestoreHold
import com.sieve.queue.core.RestoreHoldStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A normal app launch must load the persisted queue (history and interrupted work), and a service
 * restarted before that load finished must not mistake the still-empty queue for an idle one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class QueueRestoreOnLaunchTest {
    private val app: android.app.Application get() = RuntimeEnvironment.getApplication()
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private fun row(id: String, status: DownloadStatus, position: Long = 1) =
        QueueJob(id, JobSpec.Download("https://$id", listOf("-f", "best")), OutputRequest("d", "o"), status = status, position = position, title = "T$id")

    private fun persisted(vararg jobs: QueueJob) = InMemoryPersistence().also { it.store.value = jobs.associateBy { j -> j.id } }

    private fun manager(
        persistence: QueuePersistence,
        port: DownloadPort = FakeDownloadPort(),
        store: RestoreHoldStore = InMemoryRestoreHoldStore(),
    ) = QueueManager(
        JobDriver(port, FakeTranscodePort()), port, FakeTranscodePort(), persistence, FakeOutputProvider(), FakeClock(),
        initial = QueueState(maxDownloads = 2), restoreStore = store,
    )

    /** No marker yet: the launch of a user upgrading from v1.0.3 or older. */
    private fun upgrade() = InMemoryRestoreHoldStore(RestoreHold())

    private fun realScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private fun eventually(timeoutMs: Long = 8_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    // --- the launch path: AppGraph builds the repository, and that alone must restore the queue --------------

    @Test fun `creating the repository on launch loads the persisted rows`() = runTest {
        val persistence = persisted(row("done", DownloadStatus.COMPLETED, 1), row("bad", DownloadStatus.FAILED, 2))

        val repo = QueueRepository.create(app, manager(persistence), backgroundScope)
        runCurrent()

        assertEquals(setOf("done", "bad"), repo.state.value.jobs.map { it.id }.toSet())
        assertTrue(repo.rehydrated.value)
    }

    @Test fun `restored queued work starts the service, restored history does not`() = runTest {
        val history = persisted(row("done", DownloadStatus.COMPLETED))
        QueueRepository.create(app, manager(history), backgroundScope)
        runCurrent()
        assertNull("nothing to run, so no service", shadowOf(app).nextStartedService)

        val interrupted = persisted(row("cut", DownloadStatus.RUNNING))
        QueueRepository.create(app, manager(interrupted), backgroundScope)
        runCurrent()
        val started: Intent? = shadowOf(app).nextStartedService
        assertNotNull("the interrupted download must be picked up again", started)
        assertEquals(QueueService::class.java.name, started!!.component!!.className)
    }

    // --- the one-time "restore paused" migration, through the repository the UI and the receiver use ---------

    @Test fun `the first restore after an upgrade brings unfinished work back paused and starts no service`() = runTest {
        val persistence = persisted(row("cut", DownloadStatus.RUNNING, 1), row("wait", DownloadStatus.QUEUED, 2), row("done", DownloadStatus.COMPLETED, 3))

        val repo = QueueRepository.create(app, manager(persistence, store = upgrade()), backgroundScope)
        runCurrent()

        assertEquals(DownloadStatus.PAUSED, repo.state.value.job("cut")!!.status)
        assertEquals(DownloadStatus.PAUSED, repo.state.value.job("wait")!!.status)
        assertEquals(DownloadStatus.COMPLETED, repo.state.value.job("done")!!.status)
        assertEquals(setOf("cut", "wait"), repo.restore.value.heldIds)
        assertEquals(2, repo.consumeRestoreNotice())
        assertNull("nothing queued, so nothing for a service to do", shadowOf(app).nextStartedService)
    }

    @Test fun `resume all wakes the service and queues every held row`() = runTest {
        val repo = QueueRepository.create(app, manager(persisted(row("a", DownloadStatus.RUNNING, 1), row("b", DownloadStatus.QUEUED, 2)), store = upgrade()), backgroundScope)
        runCurrent()
        assertNull(shadowOf(app).nextStartedService)

        repo.resumeHeld()
        runCurrent()

        assertEquals(listOf(DownloadStatus.QUEUED, DownloadStatus.QUEUED), repo.state.value.jobs.sortedBy { it.position }.map { it.status })
        assertTrue(repo.restore.value.heldIds.isEmpty())
        assertNotNull("resumed work needs the service", shadowOf(app).nextStartedService)
    }

    @Test fun `resume all with nothing held does not start the service`() = runTest {
        val repo = QueueRepository.create(app, manager(persisted(row("done", DownloadStatus.COMPLETED))), backgroundScope)
        runCurrent()

        repo.resumeHeld()
        runCurrent()

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `dismissing the banner keeps the rows paused`() = runTest {
        val repo = QueueRepository.create(app, manager(persisted(row("a", DownloadStatus.RUNNING)), store = upgrade()), backgroundScope)
        runCurrent()

        repo.dismissRestoreBanner()
        runCurrent()

        assertTrue(repo.restore.value.bannerDismissed)
        assertEquals(setOf("a"), repo.restore.value.heldIds)
        assertEquals(DownloadStatus.PAUSED, repo.state.value.job("a")!!.status)
    }

    @Test fun `a service restarted over only held rows stops once the queue is loaded`() {
        val gate = CompletableDeferred<Unit>()
        val inner = persisted(row("cut", DownloadStatus.RUNNING))
        QueueRepository.create(app, manager(GatedPersistence(inner, gate), store = upgrade()), realScope())

        val controller = Robolectric.buildService(QueueService::class.java).create()
        val service = controller.get()
        Thread.sleep(300)
        assertFalse(shadowOf(service).isStoppedBySelf)

        gate.complete(Unit)
        assertTrue("held rows are paused work: nothing is running, so the service has nothing to stay up for",
            eventually { shadowOf(service).isStoppedBySelf })
        controller.destroy()
    }

    // --- the sticky-restart path: the service is created while the queue is still being loaded -------------

    /** loadAll waits on [gate], standing in for the Room read that is still in flight. */
    private class GatedPersistence(val inner: QueuePersistence, val gate: CompletableDeferred<Unit>) : QueuePersistence by inner {
        override suspend fun loadAll(): List<QueueJob> { gate.await(); return inner.loadAll() }
    }

    @Test fun `a restarted service stays up while the persisted queue is still loading, and with restored work after`() {
        val gate = CompletableDeferred<Unit>()
        val inner = persisted(row("cut", DownloadStatus.RUNNING))
        val hang = FakeDownloadPort { flow { awaitCancellation() } }
        val repo = QueueRepository.create(app, manager(GatedPersistence(inner, gate), hang), realScope())

        val controller = Robolectric.buildService(QueueService::class.java).create()
        val service = controller.get()
        Thread.sleep(500) // long enough for the (empty, so formally idle) initial state to have been judged
        assertFalse("an empty queue that was never loaded is not an idle queue", shadowOf(service).isStoppedBySelf)

        gate.complete(Unit)
        assertTrue(eventually { repo.state.value.jobs.any { it.id == "cut" } })
        Thread.sleep(500)
        assertFalse("restored work keeps the service alive", shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }

    @Test fun `a restarted service whose restored queue is only history stops once it is loaded`() {
        val gate = CompletableDeferred<Unit>()
        val inner = persisted(row("done", DownloadStatus.COMPLETED))
        QueueRepository.create(app, manager(GatedPersistence(inner, gate)), realScope())

        val controller = Robolectric.buildService(QueueService::class.java).create()
        val service = controller.get()
        Thread.sleep(300)
        assertFalse(shadowOf(service).isStoppedBySelf)

        gate.complete(Unit)
        assertTrue("idle after a real load: the service must still stop itself", eventually { shadowOf(service).isStoppedBySelf })
        controller.destroy()
    }
}
