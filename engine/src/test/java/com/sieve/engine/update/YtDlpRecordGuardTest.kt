package com.sieve.engine.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision (what the library has on record vs the yt-dlp that will run -> repair / no repair) and what the guard does with it.
 * The library's updater answers "already up to date" whenever GitHub's latest tag equals its record, so a record NEWER than the file
 * is the one state that must be repaired; every other state must be left exactly as it is.
 */
class YtDlpRecordGuardTest {
    private val stale = RecordVerdict.Stale("2026.08.19", "2025.11.12")

    // ---- the decision ----

    @Test fun aRecordNewerThanTheFileIsRepaired() {
        // the defect: Auto Backup restored dlpVersion=2026.08.19, the file is the APK's bundled 2025.11.12
        assertEquals(stale, YtDlpRecordGuard.verdict("2026.08.19", "2025.11.12"))
    }

    @Test fun aRecordThatNamesTheFileIsLeftAlone() {
        assertEquals(RecordVerdict.Consistent, YtDlpRecordGuard.verdict("2026.08.19", "2026.08.19"))
    }

    @Test fun aRecordOlderThanTheFileIsLeftAlone() {
        // an update that died after copying the file: GitHub's latest differs from the record, so the updater downloads by itself
        assertEquals(RecordVerdict.Consistent, YtDlpRecordGuard.verdict("2026.08.19", "2026.09.02"))
    }

    @Test fun nothingRecordedIsNothingToRepair() {
        // a fresh install: the updater has no record to trust and downloads
        assertEquals(RecordVerdict.NoRecord, YtDlpRecordGuard.verdict(null, "2025.11.12"))
        assertEquals(RecordVerdict.NoRecord, YtDlpRecordGuard.verdict("", "2025.11.12"))
        assertEquals(RecordVerdict.NoRecord, YtDlpRecordGuard.verdict("   ", null))
    }

    @Test fun aFileThatCannotBeReadLeavesTheRecordAlone() {
        assertEquals(RecordVerdict.Unreadable, YtDlpRecordGuard.verdict("2026.08.19", null))
        assertEquals(RecordVerdict.Unreadable, YtDlpRecordGuard.verdict("2026.08.19", " "))
    }

    @Test fun nightlyRevisionsCompareLikeTheUpdaterDoes() {
        assertEquals(RecordVerdict.Stale("2026.08.19.232830", "2026.08.19"), YtDlpRecordGuard.verdict("2026.08.19.232830", "2026.08.19"))
        assertEquals(RecordVerdict.Consistent, YtDlpRecordGuard.verdict("2026.08.19", "2026.08.19.232830"))
    }

    @Test fun anUnparsableTagCanNeverStartARedownloadLoop() {
        // "different" would repair on every launch if a tag and a file version were ever spelled differently; "newer" cannot
        assertEquals(RecordVerdict.Consistent, YtDlpRecordGuard.verdict("stable-2026", "2026.08.19"))
        assertEquals(RecordVerdict.Consistent, YtDlpRecordGuard.verdict("2026.08.19", "2026.08.19.dev0"))
    }

    // ---- the guard ----

    private class Record(var value: String?, private val clears: Boolean = true) : YtDlpVersionRecord {
        var reads = 0
        var clearCalls = 0
        override fun recorded(): String? = value.also { reads++ }
        override fun clear(): Boolean {
            clearCalls++
            if (clears) value = null
            return clears
        }
    }

    private class Run(val record: Record, installed: String?) {
        val lines = mutableListOf<String>()
        var fileReads = 0
        val guard = YtDlpRecordGuard(record, installedVersion = { fileReads++; installed }, log = { lines += it })
    }

    @Test fun repairClearsTheRecordOnceAndLogsOneLine() {
        val r = Run(Record("2026.08.19"), installed = "2025.11.12")
        assertEquals(YtDlpRecordGuard.Repair("2026.08.19", "2025.11.12"), r.guard.repairIfStale())
        assertNull(r.record.value)
        assertEquals(1, r.record.clearCalls)
        assertEquals(1, r.lines.size)
        assertTrue(r.lines.single(), "2026.08.19" in r.lines.single() && "2025.11.12" in r.lines.single())
        assertEquals(1, r.fileReads)
    }

    @Test fun repairingTwiceRepairsOnlyOnce() {
        val r = Run(Record("2026.08.19"), installed = "2025.11.12")
        assertTrue(r.guard.repairIfStale() != null)
        assertNull(r.guard.repairIfStale()) // the record is gone: the next start (or the next update) has nothing left to fix
        assertEquals(1, r.lines.size)
    }

    @Test fun aConsistentRecordIsNeitherTouchedNorLogged() {
        val r = Run(Record("2026.08.19"), installed = "2026.08.19")
        assertNull(r.guard.repairIfStale())
        assertEquals("2026.08.19", r.record.value)
        assertEquals(0, r.record.clearCalls)
        assertTrue(r.lines.isEmpty())
    }

    @Test fun withNothingRecordedTheFileIsNeverOpened() {
        val r = Run(Record(null), installed = "2025.11.12")
        assertNull(r.guard.repairIfStale())
        assertEquals(0, r.fileReads) // every fresh install takes this path: no cost at all
        assertEquals(0, r.record.clearCalls)
        assertTrue(r.lines.isEmpty())
    }

    @Test fun anUnreadableFileLeavesTheRecordAloneAndSaysSo() {
        val r = Run(Record("2026.08.19"), installed = null)
        assertNull(r.guard.repairIfStale())
        assertEquals("2026.08.19", r.record.value)
        assertEquals(0, r.record.clearCalls)
        assertEquals(1, r.lines.size)
    }

    @Test fun aRecordThatCannotBeClearedIsNotReportedAsRepaired() {
        val r = Run(Record("2026.08.19", clears = false), installed = "2025.11.12")
        assertNull(r.guard.repairIfStale())
        assertEquals(1, r.record.clearCalls)
        assertEquals(1, r.lines.size)
        assertTrue(r.lines.single(), "could not be reset" in r.lines.single())
        assertFalse(r.lines.single(), "repaired" in r.lines.single())
    }

    @Test fun theLogTagIsTheEnginesStartupTag() {
        assertEquals("SieveEngine", YtDlpRecordGuard.TAG)
    }
}
