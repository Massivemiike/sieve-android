package com.sieve.queue.service

import com.sieve.engine.model.DownloadProgress
import com.sieve.engine.repo.EngineEvent
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueuePersistence
import com.sieve.queue.core.QueueState
import com.sieve.queue.core.RestoreHold
import com.sieve.queue.core.RestoreHoldStore
import com.sieve.queue.core.InMemoryRestoreHoldStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-time "restore paused" migration: the first restore of a persisted queue (no marker yet) brings every
 * unfinished row back PAUSED and holds it; held rows stay paused on later launches until the user acts; every
 * other row follows the normal rule (in-flight -> QUEUED).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueManagerRestoreHeldTest {
    private fun row(id: String, status: DownloadStatus, position: Long = 1) =
        QueueJob(id, JobSpec.Download("https://$id", listOf("-f", "best")), OutputRequest("d", "o"), status = status, position = position, title = "T$id")

    private fun persisted(vararg jobs: QueueJob) = InMemoryPersistence().also { it.store.value = jobs.associateBy { j -> j.id } }

    /** The marker is absent: what a user upgrading from v1.0.3 or older has. */
    private fun upgrade() = RecordingStore(RestoreHold())

    /** Remembers every save, so a test can tell the hold was (or was not) written. */
    private class RecordingStore(initial: RestoreHold = RestoreHold.SETTLED) : RestoreHoldStore {
        private val inner = InMemoryRestoreHoldStore(initial)
        val saves = mutableListOf<RestoreHold>()
        override suspend fun load() = inner.load()
        override suspend fun save(hold: RestoreHold) { saves += hold; inner.save(hold) }
    }

    /** Ports that record which downloads actually started and then hold them open. */
    private class Runs {
        val started = mutableListOf<String>()
        val port = FakeDownloadPort { id -> started += id; flow { emit(EngineEvent.Progress(DownloadProgress(0.1f))); awaitCancellation() } }
    }

    private fun manager(
        persistence: QueuePersistence,
        store: RestoreHoldStore,
        runs: Runs = Runs(),
        output: FakeOutputProvider = FakeOutputProvider(),
        maxDownloads: Int = 2,
    ) = QueueManager(
        JobDriver(runs.port, FakeTranscodePort()), runs.port, FakeTranscodePort(), persistence, output, FakeClock(),
        initial = QueueState(maxDownloads = maxDownloads), restoreStore = store,
    )

    private fun QueueManager.status(id: String) = state.value.job(id)?.status

    private val unfinished = arrayOf(
        row("queued", DownloadStatus.QUEUED, 1), row("running", DownloadStatus.RUNNING, 2),
        row("preparing", DownloadStatus.PREPARING, 3), row("paused", DownloadStatus.PAUSED, 4),
    )
    private val unfinishedIds = setOf("queued", "running", "preparing", "paused")

    // --- launch 1: the migration --------------------------------------------------------------------------

    @Test fun `the first restore brings every unfinished row back paused and holds it`() = runTest {
        val persistence = persisted(*unfinished, row("done", DownloadStatus.COMPLETED, 5), row("bad", DownloadStatus.FAILED, 6), row("gone", DownloadStatus.CANCELLED, 7))
        val store = upgrade()
        val m = manager(persistence, store)

        m.rehydrate()

        unfinishedIds.forEach { assertEquals(it, DownloadStatus.PAUSED, m.status(it)) }
        assertEquals(DownloadStatus.COMPLETED, m.status("done"))
        assertEquals(DownloadStatus.FAILED, m.status("bad"))
        assertEquals(DownloadStatus.CANCELLED, m.status("gone"))
        assertEquals(unfinishedIds, m.restoreHold.value.heldIds)
        assertEquals("the pause is persisted, not just in memory", DownloadStatus.PAUSED, persistence.loadAll().first { it.id == "running" }.status)
        assertTrue(m.rehydrated.value)
    }

    @Test fun `nothing starts by itself after the first restore`() = runTest {
        val runs = Runs()
        val m = manager(persisted(*unfinished), upgrade(), runs)
        m.start(backgroundScope)

        m.rehydrate()
        runCurrent()

        assertTrue("a held row must not auto-start", runs.started.isEmpty())
        unfinishedIds.forEach { assertEquals(DownloadStatus.PAUSED, m.status(it)) }
    }

    @Test fun `the migration is marked done at once, with the held ids`() = runTest {
        val store = upgrade()
        manager(persisted(*unfinished, row("done", DownloadStatus.COMPLETED, 5)), store).rehydrate()

        assertEquals(RestoreHold(migrated = true, heldIds = unfinishedIds, bannerDismissed = false), store.load())
        assertEquals("one write, before anything else can change the queue", 1, store.saves.size)
    }

    @Test fun `a fresh install marks the migration done silently`() = runTest {
        val store = upgrade()
        val m = manager(persisted(), store)

        m.rehydrate()

        assertEquals(RestoreHold(migrated = true), store.load())
        assertTrue(m.restoreHold.value.heldIds.isEmpty())
        assertEquals("no banner, no snackbar", 0, m.consumeRestoreNotice())
    }

    @Test fun `a queue of only finished rows marks the migration done silently`() = runTest {
        val store = upgrade()
        val m = manager(persisted(row("done", DownloadStatus.COMPLETED), row("bad", DownloadStatus.FAILED, 2)), store)

        m.rehydrate()

        assertTrue(store.load().migrated)
        assertTrue(m.restoreHold.value.heldIds.isEmpty())
        assertEquals(0, m.consumeRestoreNotice())
        assertEquals(DownloadStatus.FAILED, m.status("bad"))
    }

    @Test fun `the one-time notice carries the count, once`() = runTest {
        val m = manager(persisted(*unfinished), upgrade())
        m.rehydrate()

        assertEquals(4, m.consumeRestoreNotice())
        assertEquals("shown once", 0, m.consumeRestoreNotice())
    }

    @Test fun `a job enqueued before the load finished is not held, the restored ones are`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val inner = persisted(row("old", DownloadStatus.RUNNING, 1))
        val slow = object : QueuePersistence by inner {
            override suspend fun loadAll(): List<QueueJob> { gate.await(); return inner.loadAll() }
        }
        val runs = Runs()
        val m = manager(slow, upgrade(), runs)
        m.start(backgroundScope)
        val loading = backgroundScope.launch { m.rehydrate() }
        runCurrent()

        m.enqueue(row("fresh", DownloadStatus.QUEUED, 1)) // e.g. a share-intent enqueue racing the Room read
        gate.complete(Unit)
        loading.join()
        runCurrent()

        assertEquals(setOf("old"), m.restoreHold.value.heldIds)
        assertEquals(DownloadStatus.PAUSED, m.status("old"))
        assertEquals(DownloadStatus.RUNNING, m.status("fresh"))
        assertEquals(listOf("fresh"), runs.started)
        assertEquals(1, m.consumeRestoreNotice())
    }

    @Test fun `a marker store that cannot be read holds the rows rather than auto-starting them`() = runTest {
        val broken = object : RestoreHoldStore {
            override suspend fun load(): RestoreHold = throw IllegalStateException("datastore corrupt")
            override suspend fun save(hold: RestoreHold) = throw IllegalStateException("datastore corrupt")
        }
        val runs = Runs()
        val m = manager(persisted(*unfinished), broken, runs)
        m.start(backgroundScope)

        m.rehydrate()
        runCurrent()

        assertTrue("the load still ends", m.rehydrated.value)
        unfinishedIds.forEach { assertEquals(DownloadStatus.PAUSED, m.status(it)) }
        assertTrue(runs.started.isEmpty())
    }

    // --- later launches -----------------------------------------------------------------------------------

    /** Runs the migration on [persistence] and returns the store a second launch would read. */
    private suspend fun afterMigration(persistence: InMemoryPersistence): RecordingStore =
        upgrade().also { manager(persistence, it).rehydrate() }

    @Test fun `held rows stay paused on every later launch, and never auto-start`() = runTest {
        val persistence = persisted(*unfinished, row("done", DownloadStatus.COMPLETED, 5))
        val store = afterMigration(persistence)

        repeat(2) {
            val runs = Runs()
            val next = manager(persistence, store, runs)
            next.start(backgroundScope)
            next.rehydrate()
            runCurrent()

            unfinishedIds.forEach { assertEquals(DownloadStatus.PAUSED, next.status(it)) }
            assertEquals(unfinishedIds, next.restoreHold.value.heldIds)
            assertTrue("launch ${it + 2}: nothing auto-starts", runs.started.isEmpty())
            assertEquals("no snackbar after the migrating launch", 0, next.consumeRestoreNotice())
        }
    }

    @Test fun `rows that are not held follow the normal rule from the next launch on`() = runTest {
        val persistence = persisted(row("held", DownloadStatus.RUNNING, 1))
        val store = afterMigration(persistence)
        // Work the user started after the migration, cut short by the process dying, and some history.
        persistence.upsertAll(listOf(row("cut", DownloadStatus.RUNNING, 2), row("wait", DownloadStatus.QUEUED, 3), row("pause", DownloadStatus.PAUSED, 4), row("done", DownloadStatus.COMPLETED, 5)))
        val runs = Runs()
        val next = manager(persistence, store, runs)
        next.start(backgroundScope)

        next.rehydrate()
        runCurrent()

        assertEquals(DownloadStatus.PAUSED, next.status("held"))
        assertEquals(setOf("held"), next.restoreHold.value.heldIds)
        assertEquals(setOf("cut", "wait"), runs.started.toSet())                     // in-flight -> QUEUED -> started (cap 2)
        assertEquals(DownloadStatus.QUEUED, persistence.loadAll().first { it.id == "pause" }.status) // Windows parity: paused -> queued
        assertEquals(DownloadStatus.COMPLETED, next.status("done"))
    }

    @Test fun `a held id whose row finished or vanished is dropped from the hold`() = runTest {
        val persistence = persisted(row("a", DownloadStatus.PAUSED, 1), row("done", DownloadStatus.COMPLETED, 2))
        val store = RecordingStore(RestoreHold(migrated = true, heldIds = setOf("a", "done", "gone")))
        val m = manager(persistence, store)

        m.rehydrate()

        assertEquals(setOf("a"), m.restoreHold.value.heldIds)
        assertEquals(setOf("a"), store.load().heldIds)
        assertEquals(DownloadStatus.COMPLETED, m.status("done"))
    }

    @Test fun `a held row whose pause never reached the database still comes back paused`() = runTest {
        // The hold is written before the rows, so a crash in between leaves the ids saved and the rows as they were.
        val persistence = persisted(row("a", DownloadStatus.RUNNING, 1), row("b", DownloadStatus.QUEUED, 2))
        val store = RecordingStore(RestoreHold(migrated = true, heldIds = setOf("a", "b")))
        val runs = Runs()
        val m = manager(persistence, store, runs)
        m.start(backgroundScope)

        m.rehydrate()
        runCurrent()

        assertEquals(DownloadStatus.PAUSED, m.status("a"))
        assertEquals(DownloadStatus.PAUSED, m.status("b"))
        assertTrue(runs.started.isEmpty())
    }

    @Test fun `a marker store that cannot be written does not break the restore`() = runTest {
        val readOnly = object : RestoreHoldStore {
            override suspend fun load() = RestoreHold()
            override suspend fun save(hold: RestoreHold) = throw java.io.IOException("disk full")
        }
        val m = manager(persisted(*unfinished), readOnly)

        m.rehydrate()

        assertTrue(m.rehydrated.value)
        unfinishedIds.forEach { assertEquals(DownloadStatus.PAUSED, m.status(it)) }
        assertEquals(unfinishedIds, m.restoreHold.value.heldIds)
    }

    // --- acting on held rows ------------------------------------------------------------------------------

    @Test fun `resuming one held row releases just that row, and it runs like any resumed row`() = runTest {
        val persistence = persisted(row("a", DownloadStatus.RUNNING, 1), row("b", DownloadStatus.RUNNING, 2))
        val store = upgrade()
        val runs = Runs()
        val m = manager(persistence, store, runs)
        m.start(backgroundScope)
        m.rehydrate()

        m.resume("a")
        runCurrent()

        assertEquals(DownloadStatus.RUNNING, m.status("a"))
        assertEquals(DownloadStatus.PAUSED, m.status("b"))
        assertEquals(setOf("b"), m.restoreHold.value.heldIds)
        assertEquals(setOf("b"), store.load().heldIds)
        assertEquals(listOf("a"), runs.started)
    }

    @Test fun `a resumed row is no longer held on the next launch, the others still are`() = runTest {
        val persistence = persisted(row("a", DownloadStatus.RUNNING, 1), row("b", DownloadStatus.RUNNING, 2))
        val store = upgrade()
        val m = manager(persistence, store)
        m.rehydrate()
        m.resume("a") // not started here (no scope): the row is persisted QUEUED

        val runs = Runs()
        val next = manager(persistence, store, runs)
        next.start(backgroundScope)
        next.rehydrate()
        runCurrent()

        assertEquals(DownloadStatus.PAUSED, next.status("b"))
        assertEquals(setOf("b"), next.restoreHold.value.heldIds)
        assertEquals(listOf("a"), runs.started)
    }

    @Test fun `cancelling a held row releases it, and it can then be removed`() = runTest {
        val persistence = persisted(row("a", DownloadStatus.PAUSED, 1), row("b", DownloadStatus.PAUSED, 2))
        val store = upgrade()
        val out = FakeOutputProvider()
        val m = manager(persistence, store, output = out)
        m.rehydrate()

        m.cancel("a")

        assertEquals(DownloadStatus.CANCELLED, m.status("a"))
        assertEquals(setOf("b"), m.restoreHold.value.heldIds)
        assertEquals(setOf("b"), store.load().heldIds)
        assertEquals(listOf("a"), out.cleaned)

        m.remove("a")
        assertEquals(null, m.state.value.job("a"))
        assertEquals(setOf("b"), m.restoreHold.value.heldIds)
    }

    @Test fun `clear finished and a live row's progress leave the hold alone`() = runTest {
        val persistence = persisted(row("held", DownloadStatus.RUNNING, 1), row("done", DownloadStatus.COMPLETED, 2))
        val store = upgrade()
        val m = manager(persistence, store)
        m.start(backgroundScope)
        m.rehydrate()
        val savesAfterLoad = store.saves.size

        m.clearFinished()
        m.enqueue(row("new", DownloadStatus.QUEUED, 3))
        runCurrent()

        assertEquals(setOf("held"), m.restoreHold.value.heldIds)
        assertEquals(DownloadStatus.PAUSED, m.status("held"))
        assertEquals("the hold is not rewritten for unrelated changes", savesAfterLoad, store.saves.size)
    }

    @Test fun `resume all releases every held row, clears the hold and respects the download cap`() = runTest {
        val persistence = persisted(row("a", DownloadStatus.RUNNING, 1), row("b", DownloadStatus.QUEUED, 2), row("c", DownloadStatus.PAUSED, 3), row("done", DownloadStatus.COMPLETED, 4))
        val store = upgrade()
        val runs = Runs()
        val m = manager(persistence, store, runs, maxDownloads = 2)
        m.start(backgroundScope)
        m.rehydrate()

        m.resumeHeld()
        runCurrent()

        assertTrue(m.restoreHold.value.heldIds.isEmpty())
        assertTrue(store.load().heldIds.isEmpty())
        assertEquals(listOf("a", "b"), runs.started)                              // position order, 2 at a time
        assertEquals(DownloadStatus.QUEUED, m.status("c"))                        // waits for a slot, like any queued row
        assertEquals(DownloadStatus.COMPLETED, m.status("done"))
        assertTrue("the migration stays done", store.load().migrated)
    }

    @Test fun `resume all with nothing held does nothing`() = runTest {
        val store = RecordingStore(RestoreHold.SETTLED)
        val m = manager(persisted(row("bad", DownloadStatus.FAILED, 1)), store)
        m.start(backgroundScope)
        m.rehydrate()
        val before = m.state.value

        m.resumeHeld()
        runCurrent()

        assertEquals(before, m.state.value)
        assertTrue(store.saves.isEmpty())
    }

    @Test fun `resume all leaves a row the user paused themselves alone`() = runTest {
        val port = CancellableDownloadPort()
        val store = upgrade()
        val m = QueueManager(
            JobDriver(port, FakeTranscodePort()), port, FakeTranscodePort(), persisted(row("old", DownloadStatus.RUNNING, 1)),
            FakeOutputProvider(), FakeClock(), initial = QueueState(maxDownloads = 2), restoreStore = store,
        )
        m.start(backgroundScope)
        m.rehydrate()
        m.enqueue(row("manual", DownloadStatus.QUEUED, 2))
        m.state.first { it.job("manual")?.status == DownloadStatus.RUNNING }
        m.pause("manual")
        m.state.first { it.job("manual")?.status == DownloadStatus.PAUSED }

        m.resumeHeld()
        runCurrent()

        assertEquals("only held rows are resumed", DownloadStatus.PAUSED, m.status("manual"))
        assertEquals(DownloadStatus.RUNNING, m.status("old"))
        assertTrue(m.restoreHold.value.heldIds.isEmpty())
    }

    @Test fun `dismissing the banner hides it for good while the rows stay paused and held`() = runTest {
        val persistence = persisted(*unfinished)
        val store = upgrade()
        val m = manager(persistence, store)
        m.rehydrate()

        m.dismissRestoreBanner()

        assertTrue(m.restoreHold.value.bannerDismissed)
        assertEquals(unfinishedIds, m.restoreHold.value.heldIds)
        unfinishedIds.forEach { assertEquals(DownloadStatus.PAUSED, m.status(it)) }
        assertTrue(store.load().bannerDismissed)

        val runs = Runs()
        val next = manager(persistence, store, runs)
        next.start(backgroundScope)
        next.rehydrate()
        runCurrent()

        assertTrue("still dismissed on the next launch", next.restoreHold.value.bannerDismissed)
        assertEquals(unfinishedIds, next.restoreHold.value.heldIds)
        assertTrue(runs.started.isEmpty())
    }

    @Test fun `a row resumed after the banner was dismissed still leaves the hold`() = runTest {
        val persistence = persisted(row("a", DownloadStatus.PAUSED, 1), row("b", DownloadStatus.PAUSED, 2))
        val store = upgrade()
        val m = manager(persistence, store)
        m.rehydrate()
        m.dismissRestoreBanner()

        m.resume("a")

        assertEquals(setOf("b"), m.restoreHold.value.heldIds)
        assertEquals(RestoreHold(migrated = true, heldIds = setOf("b"), bannerDismissed = true), store.load())
    }

    @Test fun `with the default store a restore is the plain one - paused and in-flight rows come back queued`() = runTest {
        val m = QueueManager(
            JobDriver(FakeDownloadPort(), FakeTranscodePort()), FakeDownloadPort(), FakeTranscodePort(),
            persisted(*unfinished), FakeOutputProvider(), FakeClock(), initial = QueueState(maxDownloads = 2),
        )

        m.rehydrate()

        unfinishedIds.forEach { assertEquals(DownloadStatus.QUEUED, m.status(it)) }
        assertTrue(m.restoreHold.value.heldIds.isEmpty())
        assertFalse(m.restoreHold.value.bannerDismissed)
        assertEquals(0, m.consumeRestoreNotice())
    }
}
