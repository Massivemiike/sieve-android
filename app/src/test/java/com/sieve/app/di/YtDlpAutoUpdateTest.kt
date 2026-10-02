package com.sieve.app.di

import com.sieve.engine.repo.AnalyzeOutcome
import com.sieve.engine.repo.EngineEvent
import com.sieve.engine.repo.YtDlpEngine
import com.sieve.engine.update.UpdateChannel
import com.sieve.engine.update.UpdateCheck
import com.sieve.engine.update.UpdateResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The launch-time yt-dlp update ([YtDlpAutoUpdate]): throttled to once per 12 h, waits for running downloads, stamps the throttle only
 * on success. New: it first asks the engine to repair a version record that outlived its yt-dlp file (Auto Backup restoring
 * youtubedl-android's prefs onto a reinstall), and after a repair the update is due NOW whatever the throttle says.
 */
class YtDlpAutoUpdateTest {
    private val hour = 60 * 60 * 1000L
    private val now = 1_000 * hour

    private class FakeEngine(
        /** What repairVersionRecord() reports. */
        var repairs: Boolean = false,
        var updateResult: UpdateResult = UpdateResult(true, "DONE"),
        var repairThrows: Boolean = false,
        var updateThrows: Boolean = false,
    ) : YtDlpEngine {
        val calls = mutableListOf<String>()
        override suspend fun analyze(url: String, cookiesBrowser: String?, cookiesFile: String?, proxy: String?, userAgent: String?): AnalyzeOutcome =
            throw NotImplementedError()
        override fun download(id: String, url: String, args: List<String>): Flow<EngineEvent> = emptyFlow()
        override fun cancel(id: String): Boolean = true
        override suspend fun version(): String? = "2026.08.19"
        override suspend fun checkUpdate(): UpdateCheck = throw NotImplementedError()
        override suspend fun repairVersionRecord(): Boolean {
            calls += "repair"
            if (repairThrows) error("prefs unavailable")
            return repairs
        }
        override suspend fun doUpdate(channel: UpdateChannel): UpdateResult {
            calls += "update:$channel"
            if (updateThrows) error("offline")
            return updateResult
        }
    }

    /** The throttle stamp (what DataStore holds) and the idle wait, with the order of everything recorded. */
    private inner class Harness(val engine: FakeEngine, var stamp: Long = 0L, var idle: Boolean = true) {
        val events get() = engine.calls
        val update = YtDlpAutoUpdate(
            engine = engine,
            lastUpdatedAt = { events += "read-stamp"; stamp },
            stampUpdated = { stamp = it; events += "stamp" },
            forgetStamp = { stamp = 0L; events += "forget-stamp" },
            awaitIdle = { events += "await-idle"; idle },
            now = { now },
        )
    }

    // ---- unchanged behaviour (RC): throttle, idle wait, stamp-on-success ----

    @Test fun aFreshInstallUpdatesAndStampsTheThrottle() = runTest {
        val h = Harness(FakeEngine())
        h.update.run()
        assertEquals(listOf("repair", "read-stamp", "await-idle", "update:STABLE", "stamp"), h.events)
        assertEquals(now, h.stamp)
    }

    @Test fun aRecentUpdateIsThrottledForTwelveHours() = runTest {
        val h = Harness(FakeEngine(), stamp = now - 11 * hour)
        h.update.run()
        assertEquals(listOf("repair", "read-stamp"), h.events) // nothing repaired, nothing due
        assertEquals(now - 11 * hour, h.stamp)
    }

    @Test fun anOlderUpdateIsDueAgain() = runTest {
        val h = Harness(FakeEngine(), stamp = now - 13 * hour)
        h.update.run()
        assertTrue("update:STABLE" in h.events)
        assertEquals(now, h.stamp)
    }

    @Test fun downloadsThatNeverEndSkipTheUpdateAndLeaveTheStamp() = runTest {
        val h = Harness(FakeEngine(), idle = false)
        h.update.run()
        assertEquals(listOf("repair", "read-stamp", "await-idle"), h.events)
        assertEquals(0L, h.stamp)
    }

    @Test fun aFailedUpdateDoesNotStampTheThrottle() = runTest {
        val h = Harness(FakeEngine(updateResult = UpdateResult(false, "offline")), stamp = now - 13 * hour)
        h.update.run()
        assertEquals(now - 13 * hour, h.stamp)
        assertTrue("stamp" !in h.events)
    }

    @Test fun anUpdateThatThrowsIsSwallowed() = runTest {
        val h = Harness(FakeEngine(updateThrows = true))
        h.update.run() // must not propagate: the next open tries again
        assertEquals(0L, h.stamp)
    }

    // ---- the repair ----

    @Test fun aRepairMakesTheUpdateDueEvenInsideTheThrottleWindow() = runTest {
        // The defect: restored prefs say 2026.08.19, the bundled 2025.11.12 runs. After the engine clears the record the update must go
        // and download now, not wait out a throttle stamped by an earlier (real) update.
        val h = Harness(FakeEngine(repairs = true), stamp = now - 1 * hour)
        h.update.run()
        assertEquals(listOf("repair", "forget-stamp", "await-idle", "update:STABLE", "stamp"), h.events)
        assertEquals(now, h.stamp)
    }

    @Test fun theRepairComesBeforeAnythingElse() = runTest {
        val h = Harness(FakeEngine(repairs = true))
        h.update.run()
        assertEquals("repair", h.events.first())
    }

    @Test fun aRepairedInstallWhoseUpdateFailsRetriesOnTheNextOpen() = runTest {
        // First open: repaired, offline, update fails. The record is gone now, so the next open repairs nothing: only a forgotten stamp
        // keeps that open from being throttled for 12 h.
        val engine = FakeEngine(repairs = true, updateResult = UpdateResult(false, "offline"))
        val h = Harness(engine, stamp = now - 1 * hour)
        h.update.run()
        assertEquals(0L, h.stamp)
        engine.calls.clear()
        engine.repairs = false
        engine.updateResult = UpdateResult(true, "DONE")
        h.update.run()
        assertTrue("update:STABLE" in engine.calls, engine.calls.toString())
        assertEquals(now, h.stamp)
    }

    @Test fun aRepairedInstallWhoseUpdateIsSkippedForRunningDownloadsRetriesOnTheNextOpen() = runTest {
        val h = Harness(FakeEngine(repairs = true), stamp = now - 1 * hour, idle = false)
        h.update.run()
        assertEquals(0L, h.stamp)
        assertTrue("update:STABLE" !in h.events)
    }

    @Test fun aRepairThatThrowsStillGoesOnToTheNormalUpdateLogic() = runTest {
        val h = Harness(FakeEngine(repairThrows = true), stamp = now - 13 * hour)
        h.update.run()
        assertTrue("update:STABLE" in h.events)
    }

    @Test fun withoutARepairTheThrottleStillHolds() = runTest {
        val h = Harness(FakeEngine(repairs = false), stamp = now - 1 * hour)
        h.update.run()
        assertTrue("update:STABLE" !in h.events)
        assertEquals(now - 1 * hour, h.stamp)
    }
}
