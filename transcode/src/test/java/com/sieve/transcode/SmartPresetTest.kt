package com.sieve.transcode

import com.sieve.transcode.args.SmartPreset
import com.sieve.transcode.catalog.TranscodePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 9: probe → starting preset. Height is the SECOND number in the resolution string. */
class SmartPresetTest {

    @Test fun videoResolutionsMapByHeight() {
        assertEquals("h265-1080", SmartPreset.pick("h264", "1920x1080"))
        assertEquals("h265-4k", SmartPreset.pick("hevc", "3840x2160"))
        assertEquals("h264-1080", SmartPreset.pick("vp9", "1280x720"))
    }

    @Test fun smallOrSingleNumberResolutionsReturnNull() {
        assertNull(SmartPreset.pick("h264", "640x480"))
        assertNull(SmartPreset.pick("h264", "1080")) // single number → height 0
    }

    @Test fun audioOnlySourcesMapToAudioPresets() {
        assertEquals("aac-256", SmartPreset.pick("aac", "—"))
        assertEquals("aac-256", SmartPreset.pick("", "—"))
        assertEquals("mp3-320", SmartPreset.pick("mp3", ""))
        assertEquals("flac", SmartPreset.pick("flac", ""))
        assertEquals("opus-160", SmartPreset.pick("opus", ""))
    }

    @Test fun everyReturnedIdExistsInTheCatalog() {
        val ids = listOf(
            SmartPreset.pick("h264", "1920x1080"),
            SmartPreset.pick("hevc", "3840x2160"),
            SmartPreset.pick("vp9", "1280x720"),
            SmartPreset.pick("aac", "—"),
            SmartPreset.pick("mp3", ""),
        ).filterNotNull()
        for (id in ids) assertTrue("$id missing from catalog", TranscodePresets.find(id) != null)
    }
}
