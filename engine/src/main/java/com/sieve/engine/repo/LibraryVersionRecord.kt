package com.sieve.engine.repo

import android.content.SharedPreferences
import com.sieve.engine.update.YtDlpVersionRecord

/**
 * youtubedl-android's record of the yt-dlp it downloaded: two string prefs in `shared_prefs/youtubedl-android.xml`
 * (written by its updater after a download, read by `YoutubeDL.version()` and by the updater's "already up to date"
 * test). The library offers no way to reset them, and the names are private constants of its updater, so
 * `LibraryRecordPinTest` fails when a library bump renames any of them.
 *
 * The same file also holds `pythonLibVersion` and `ffmpegLibVersion`; they are left alone (the library re-extracts
 * those itself when the unpacked directory is missing).
 */
class LibraryVersionRecord(private val prefs: SharedPreferences) : YtDlpVersionRecord {
    override fun recorded(): String? = prefs.getString(KEY_VERSION, null)

    override fun clear(): Boolean = prefs.edit().remove(KEY_VERSION).remove(KEY_VERSION_NAME).commit()

    companion object {
        /** `Context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)`; also the file name the backup rules must exclude (see [PREFS_FILE]). */
        const val PREFS_NAME = "youtubedl-android"
        const val PREFS_FILE = "$PREFS_NAME.xml"
        const val KEY_VERSION = "dlpVersion"
        const val KEY_VERSION_NAME = "dlpVersionName"
    }
}
