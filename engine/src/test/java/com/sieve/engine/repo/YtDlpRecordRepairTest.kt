package com.sieve.engine.repo

import com.sieve.engine.update.GithubReleaseApi
import com.sieve.engine.update.UpdateResult
import com.sieve.engine.update.YtDlpRecordGuard
import com.sieve.engine.update.YtDlpVersionRecord
import com.sieve.engine.update.YtDlpZipapp
import com.sieve.engine.update.ZipappFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The defect: after a reinstall Auto Backup restores youtubedl-android's prefs (dlpVersion = the yt-dlp the previous install had
 * updated to) but not the engine files, so the APK's bundled, older yt-dlp runs while the library's updater and `version()` read the
 * restored number: "up to date", forever. These tests run the real [YtDlpRecordGuard] and the real zipapp reader against an emulation of
 * the library's updater (its bytecode: `checkForUpdate` returns null when the release's tag_name equals the dlpVersion pref, and the
 * update then does nothing), through the real [YtDlpEngineImpl].
 */
class YtDlpRecordRepairTest {
    @get:Rule val tmp = TemporaryFolder()

    private val github = object : GithubReleaseApi { override suspend fun latestTag(): String? = null }

    /** youtubedl-android's updater + prefs + the yt-dlp file, as far as the version record is concerned. */
    private inner class EmulatedLibrary(var record: String?, val latestTag: String, bundled: String) : YoutubeDLClient {
        val ytDlp: File = ZipappFixture.write(File(tmp.newFolder(), "yt-dlp"), bundled)
        val guardLog = CopyOnWriteArrayList<String>()
        var downloads = 0

        private val guard = YtDlpRecordGuard(
            record = object : YtDlpVersionRecord {
                override fun recorded(): String? = record
                override fun clear(): Boolean { record = null; return true }
            },
            installedVersion = { YtDlpZipapp.version(ytDlp) },
            log = { guardLog += it },
        )

        val installed: String? get() = YtDlpZipapp.version(ytDlp)

        override fun version(): String? = record
        override fun execute(processId: String, url: String, options: List<String>, onProgress: (Float, Long, String) -> Unit) = ExecResult(0, "{}", "")
        override fun destroy(processId: String): Boolean = true

        /** YoutubeDLUpdater.update(): same tag as the record means "already up to date" and nothing is downloaded; otherwise file + record are replaced. */
        override fun update(nightly: Boolean): String {
            if (latestTag == record) return "ALREADY_UP_TO_DATE"
            ZipappFixture.write(ytDlp, latestTag)
            record = latestTag
            downloads++
            return "DONE"
        }

        override fun repairVersionRecord(): YtDlpRecordGuard.Repair? = guard.repairIfStale()
    }

    /** The same library with the repair taken out: what the 1.0.3 / RC engine does. */
    private class WithoutTheRepair(private val library: YoutubeDLClient) : YoutubeDLClient by library {
        override fun repairVersionRecord(): YtDlpRecordGuard.Repair? = null
    }

    private fun engine(client: YoutubeDLClient) = YtDlpEngineImpl(client, github, io = Dispatchers.IO)

    @Test fun withoutTheRepairARestoredRecordMakesTheUpdateSayCurrentAndDownloadNothing() = runBlocking {
        // Reproduces the device finding: record 2026.08.19 restored, bundled 2025.11.12 on disk, update "done" and the file untouched.
        val library = EmulatedLibrary(record = "2026.08.19", latestTag = "2026.08.19", bundled = "2025.11.12")
        assertEquals(UpdateResult(true, "ALREADY_UP_TO_DATE"), engine(WithoutTheRepair(library)).doUpdate())
        assertEquals(0, library.downloads)
        assertEquals("2025.11.12", library.installed)
        assertEquals("2026.08.19", engine(WithoutTheRepair(library)).version()) // and Settings keeps reading the restored number
    }

    @Test fun withTheRepairTheSameStateDownloadsTheCurrentYtDlp() = runBlocking {
        val library = EmulatedLibrary(record = "2026.08.19", latestTag = "2026.08.19", bundled = "2025.11.12")
        assertEquals(UpdateResult(true, "DONE"), engine(library).doUpdate())
        assertEquals(1, library.downloads)
        assertEquals("2026.08.19", library.installed)
        assertEquals("2026.08.19", library.record)
        assertEquals(1, library.guardLog.size) // exactly one SieveEngine line saying what was repaired
        assertTrue(library.guardLog.single(), "2026.08.19" in library.guardLog.single() && "2025.11.12" in library.guardLog.single())
    }

    @Test fun theLaunchPathRepairsFirstThenTheUpdateDownloads() = runBlocking {
        val library = EmulatedLibrary(record = "2026.08.19", latestTag = "2026.08.19", bundled = "2025.11.12")
        val engine = engine(library)
        assertTrue(engine.repairVersionRecord())          // what the launch-time job asks first
        assertNull(engine.version())                       // the record no longer claims a yt-dlp that is not there
        assertFalse(engine.repairVersionRecord())          // nothing left to repair
        assertEquals(UpdateResult(true, "DONE"), engine.doUpdate())
        assertEquals("2026.08.19", engine.version())
        assertEquals("2026.08.19", library.installed)
        assertEquals(1, library.guardLog.size)
    }

    @Test fun anInstallWhoseRecordMatchesItsFileIsLeftAlone() = runBlocking {
        val library = EmulatedLibrary(record = "2026.08.19", latestTag = "2026.08.19", bundled = "2026.08.19")
        val engine = engine(library)
        assertFalse(engine.repairVersionRecord())
        assertEquals(UpdateResult(true, "ALREADY_UP_TO_DATE"), engine.doUpdate())
        assertEquals(0, library.downloads)
        assertTrue(library.guardLog.isEmpty())
    }

    @Test fun aFreshInstallHasNoRecordAndDownloads() = runBlocking {
        val library = EmulatedLibrary(record = null, latestTag = "2026.08.19", bundled = "2025.11.12")
        val engine = engine(library)
        assertFalse(engine.repairVersionRecord())
        assertEquals(UpdateResult(true, "DONE"), engine.doUpdate())
        assertEquals("2026.08.19", library.installed)
        assertTrue(library.guardLog.isEmpty())
    }

    @Test fun aNewerYtDlpThanTheRecordIsNotTouched() = runBlocking {
        val library = EmulatedLibrary(record = "2026.08.19", latestTag = "2026.09.02", bundled = "2026.09.02")
        assertFalse(engine(library).repairVersionRecord())
        assertEquals("2026.08.19", library.record)
    }

    @Test fun aYtDlpFileThatCannotBeReadLeavesTheRecordAlone() = runBlocking {
        val library = EmulatedLibrary(record = "2026.08.19", latestTag = "2026.08.19", bundled = "2025.11.12")
        library.ytDlp.writeText("not a zip")
        assertFalse(engine(library).repairVersionRecord())
        assertEquals("2026.08.19", library.record)
    }

    // ---- how the repair sits with the engine's file lock and with a client that fails ----

    private class RecordingClient(val repair: () -> YtDlpRecordGuard.Repair? = { null }) : YoutubeDLClient {
        val log = CopyOnWriteArrayList<String>()
        val updateEntered = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        var blockUpdate = false
        override fun version(): String? = null
        override fun execute(processId: String, url: String, options: List<String>, onProgress: (Float, Long, String) -> Unit) = ExecResult(0, "{}", "")
        override fun destroy(processId: String): Boolean = true
        override fun update(nightly: Boolean): String {
            log += "update-start"
            updateEntered.countDown()
            if (blockUpdate) releaseUpdate.await(10, TimeUnit.SECONDS)
            log += "update-end"
            return "DONE"
        }
        override fun repairVersionRecord(): YtDlpRecordGuard.Repair? {
            log += "repair"
            return repair()
        }
    }

    @Test fun everyUpdateRepairsFirst() = runBlocking {
        // the launch-time job AND Settings > Update both go through doUpdate: neither can reach the library with a stale record
        val client = RecordingClient()
        engine(client).doUpdate()
        assertEquals(listOf("repair", "update-start", "update-end"), client.log.toList())
    }

    @Test fun repairReportsWhetherItRepairedAnything() = runBlocking {
        assertTrue(engine(RecordingClient(repair = { YtDlpRecordGuard.Repair("2026.08.19", "2025.11.12") })).repairVersionRecord())
        assertFalse(engine(RecordingClient()).repairVersionRecord())
    }

    @Test fun aRepairThatThrowsIsReportedAsNothingRepaired() = runBlocking {
        // the launch-time job must go on to the update whatever the check does
        val client = RecordingClient(repair = { error("prefs unavailable") })
        assertFalse(engine(client).repairVersionRecord())
        assertEquals(listOf("repair"), client.log.toList())
    }

    @Test fun theLibraryClientNeverRepairsUnlessAskedTo() {
        // the interface default: a client without a repair (every fake, and an engine built for tests) behaves exactly as before
        val bare = object : YoutubeDLClient {
            override fun version(): String? = null
            override fun execute(processId: String, url: String, options: List<String>, onProgress: (Float, Long, String) -> Unit) = ExecResult(0, "", "")
            override fun destroy(processId: String): Boolean = true
            override fun update(nightly: Boolean): String = "DONE"
        }
        assertNull(bare.repairVersionRecord())
    }

    @Test fun repairWaitsForARunningUpdate() = runBlocking {
        // The repair edits the record the updater is rewriting: it takes the read side of the engine's file lock, the update the write side.
        val client = RecordingClient().apply { blockUpdate = true }
        val engine = engine(client)
        val update = async(Dispatchers.IO) { engine.doUpdate() }
        assertTrue(client.updateEntered.await(5, TimeUnit.SECONDS))
        val repair = async(Dispatchers.IO) { engine.repairVersionRecord() }
        Thread.sleep(300)
        assertEquals("the repair ran under a running update", listOf("repair", "update-start"), client.log.toList()) // doUpdate's own repair came first
        client.releaseUpdate.countDown()
        withTimeout(5_000) { update.await(); repair.await() }
        assertEquals(listOf("repair", "update-start", "update-end", "repair"), client.log.toList())
    }
}
