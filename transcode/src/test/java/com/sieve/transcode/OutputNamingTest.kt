package com.sieve.transcode

import com.sieve.transcode.output.OutputNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Task 10: filename sanitization + output-path resolution. */
class OutputNamingTest {

    @Test fun sanitizeName_stripsIllegalCharsAndTrailingExt() {
        assertEquals("my_clip_final", OutputNaming.sanitizeName("my:clip/final.mp4"))
    }

    @Test fun sanitizeName_blankBecomesNull() {
        assertNull(OutputNaming.sanitizeName("   "))
    }

    @Test fun sanitizeExt_normalizesDotAndCase() {
        assertEquals("mp4", OutputNaming.sanitizeExt(".MP4"))
        assertEquals("mkv", OutputNaming.sanitizeExt("mkv"))
        assertNull(OutputNaming.sanitizeExt("."))
        assertNull(OutputNaming.sanitizeExt(null))
    }

    @Test fun resolve_withoutName_appendsPresetIdSuffix() {
        assertEquals(
            "/sd/out/clip_h265-1080.mp4",
            OutputNaming.resolveOutputPath("/sd/out", "clip.mkv", "h265-1080", "mp4", null, null),
        )
    }

    @Test fun resolve_withName_hasNoSuffix() {
        assertEquals(
            "/sd/out/final.mp4",
            OutputNaming.resolveOutputPath("/sd/out", "clip.mkv", "h265-1080", "mp4", "final", null),
        )
    }

    @Test fun resolve_outputExtOverridesPresetExt() {
        assertEquals(
            "/sd/out/clip_av1-1080.mkv",
            OutputNaming.resolveOutputPath("/sd/out", "clip.webm", "av1-1080", "webm", null, "mkv"),
        )
    }

    @Test fun resolve_twoPresetsSameSource_giveDistinctPaths() {
        val a = OutputNaming.resolveOutputPath("/sd/out", "clip.mkv", "h264-1080", "mp4", null, null)
        val b = OutputNaming.resolveOutputPath("/sd/out", "clip.mkv", "h265-1080", "mp4", null, null)
        assertNotEquals(a, b)
    }
}
