package com.sieve.engine.update

/** What youtubedl-android has on record about the yt-dlp it downloaded (its `youtubedl-android` shared preferences). */
interface YtDlpVersionRecord {
    /** The recorded version (a GitHub release tag such as "2026.08.19"), or null when nothing is recorded. */
    fun recorded(): String?

    /** Forgets the record, so the library's updater no longer believes yt-dlp is current. False when it could not be saved. */
    fun clear(): Boolean
}

/** How the library's record of yt-dlp compares with the yt-dlp file that will actually run. */
sealed interface RecordVerdict {
    /** Nothing recorded (a fresh install): the updater has nothing to wrongly trust and downloads. */
    data object NoRecord : RecordVerdict

    /** The yt-dlp file's own version could not be read, so the record can be neither confirmed nor refuted. */
    data object Unreadable : RecordVerdict

    /** The record names the version on disk (or an older one, which the updater replaces by itself). */
    data object Consistent : RecordVerdict

    /** The record claims a NEWER yt-dlp than the one on disk, so the updater would answer "up to date" and never download. */
    data class Stale(val recorded: String, val installed: String) : RecordVerdict
}

/**
 * youtubedl-android's updater decides "already up to date" by comparing GitHub's latest tag with ITS OWN record
 * (shared_prefs/youtubedl-android.xml), never with the yt-dlp file. The two part ways whenever the prefs outlive the
 * file: Auto Backup restores the prefs on a reinstall or a new phone but not `noBackupFilesDir`, so the APK's bundled
 * (old) yt-dlp runs while every version check reads the restored (new) number; a failed update likewise leaves the
 * bundled yt-dlp in place under the previous record. This guard notices that and forgets the record, so the next update
 * goes and downloads.
 */
class YtDlpRecordGuard(
    private val record: YtDlpVersionRecord,
    /** The version of the yt-dlp file that will run; null when it cannot be read. May block on disk I/O. */
    private val installedVersion: () -> String?,
    private val log: (String) -> Unit = {},
) {
    data class Repair(val recorded: String, val installed: String)

    /**
     * Clears the record when it is stale. Returns what was repaired, or null when nothing was (the verdict is logged only when it needs
     * attention). With nothing recorded (every fresh install) the yt-dlp file is not even opened.
     */
    fun repairIfStale(): Repair? {
        val recorded = record.recorded()
        if (recorded.isNullOrBlank()) return null
        return repair(verdict(recorded, installedVersion()))
    }

    private fun repair(verdict: RecordVerdict): Repair? = when (verdict) {
        is RecordVerdict.Stale ->
            if (record.clear()) {
                log(
                    "yt-dlp record repaired: youtubedl-android had recorded ${verdict.recorded} but the yt-dlp on disk is ${verdict.installed}; " +
                        "cleared the record so the update downloads the current yt-dlp",
                )
                Repair(verdict.recorded, verdict.installed)
            } else {
                log("yt-dlp version record is stale (recorded ${verdict.recorded}, on disk ${verdict.installed}) but could not be reset")
                null
            }
        RecordVerdict.Unreadable -> {
            log("yt-dlp version on disk could not be read; the version record is left as it is")
            null
        }
        RecordVerdict.NoRecord, RecordVerdict.Consistent -> null
    }

    companion object {
        /** Log tag of the engine's start-up lines. */
        const val TAG = "SieveEngine"

        /**
         * Only a record NEWER than the file is a defect: the updater trusts it and skips the download. A record older than the file
         * (an interrupted update) is harmless, since GitHub's latest then differs from it and the updater downloads anyway. Comparing
         * "newer" rather than "different" also means an odd tag format can never put the app into a re-download loop.
         */
        fun verdict(recorded: String?, installed: String?): RecordVerdict {
            val rec = recorded?.trim().orEmpty()
            if (rec.isEmpty()) return RecordVerdict.NoRecord
            val inst = installed?.trim().orEmpty()
            if (inst.isEmpty()) return RecordVerdict.Unreadable
            return if (VersionCompare.isNewer(rec, inst)) RecordVerdict.Stale(rec, inst) else RecordVerdict.Consistent
        }
    }
}
