package com.sieve.engine.repo

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LibraryVersionRecord] edits youtubedl-android's private prefs by name, because the library offers no way to reset them. These tests pin
 * the names to the library's own classes (a library bump that renames one fails here, not silently on a phone) and check that the reset
 * touches exactly the two keys that make the updater say "up to date".
 */
class LibraryRecordPinTest {
    /** The text of a library class file, one char per byte. A string constant sits in the pool as tag 1, a two-byte length, then its bytes. */
    private fun classText(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream(name.replace('.', '/') + ".class")
            ?: error("$name is not on the test classpath: has the youtubedl-android layout changed?")
        return stream.use { String(it.readBytes(), Charsets.ISO_8859_1) }
    }

    /** True when the class has exactly this string as a constant (not merely as part of a longer one: "dlpVersion" is inside "dlpVersionName"). */
    private fun hasConstant(classText: String, constant: String): Boolean {
        require(constant.length < 256 && constant.all { it.code < 128 })
        return ("\u0001\u0000" + constant.length.toChar() + constant) in classText
    }

    @Test fun thePrefsFileNameIsTheLibrarys() {
        val helper = classText("com.yausername.youtubedl_common.SharedPrefsHelper")
        assertTrue("SharedPrefsHelper no longer names \"${LibraryVersionRecord.PREFS_NAME}\"", hasConstant(helper, LibraryVersionRecord.PREFS_NAME))
        assertEquals("youtubedl-android.xml", LibraryVersionRecord.PREFS_FILE)
    }

    @Test fun theRecordKeysAreTheUpdatersOwn() {
        val updater = classText("com.yausername.youtubedl_android.YoutubeDLUpdater")
        assertTrue("YoutubeDLUpdater no longer uses \"${LibraryVersionRecord.KEY_VERSION}\"", hasConstant(updater, LibraryVersionRecord.KEY_VERSION))
        assertTrue("YoutubeDLUpdater no longer uses \"${LibraryVersionRecord.KEY_VERSION_NAME}\"", hasConstant(updater, LibraryVersionRecord.KEY_VERSION_NAME))
    }

    @Test fun theUpdaterComparesGithubsTagWithItsRecord() {
        // The mechanism the repair exists for: checkForUpdate() compares the release's tag_name with the dlpVersion pref and answers "already
        // up to date" on a match, without looking at the yt-dlp file. If a library bump changes that, this fails so the repair is re-examined.
        val updater = classText("com.yausername.youtubedl_android.YoutubeDLUpdater")
        assertTrue(hasConstant(updater, "tag_name"))
        assertTrue(hasConstant(updater, LibraryVersionRecord.KEY_VERSION))
    }

    @Test fun clearForgetsTheTwoKeysAndOnlyThose() {
        val prefs = FakePrefs(
            mutableMapOf(
                "dlpVersion" to "2026.08.19",
                "dlpVersionName" to "yt-dlp 2026.08.19",
                "pythonLibVersion" to "31337",
                "ffmpegLibVersion" to "4711",
            ),
        )
        val record = LibraryVersionRecord(prefs)
        assertEquals("2026.08.19", record.recorded())
        assertTrue(record.clear())
        assertNull(record.recorded())
        assertEquals(mapOf("pythonLibVersion" to "31337", "ffmpegLibVersion" to "4711"), prefs.values)
    }

    @Test fun clearOnAnEmptyRecordIsHarmless() {
        val record = LibraryVersionRecord(FakePrefs(mutableMapOf()))
        assertNull(record.recorded())
        assertTrue(record.clear())
    }

    @Test fun clearReportsAFailedCommit() {
        assertFalse(LibraryVersionRecord(FakePrefs(mutableMapOf("dlpVersion" to "2026.08.19"), commits = false)).clear())
    }

    private class FakePrefs(val values: MutableMap<String, String>, private val commits: Boolean = true) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = FakeEditor()

        private inner class FakeEditor : SharedPreferences.Editor {
            private val removed = mutableSetOf<String>()
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removed += key
                return this
            }
            override fun clear(): SharedPreferences.Editor = this
            override fun commit(): Boolean {
                if (commits) values.keys.removeAll(removed)
                return commits
            }
            override fun apply() { commit() }
        }
    }
}
