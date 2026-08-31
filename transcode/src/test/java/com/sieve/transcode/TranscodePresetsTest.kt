package com.sieve.transcode

import com.sieve.transcode.catalog.PresetRail
import com.sieve.transcode.catalog.TranscodePresets
import com.sieve.transcode.model.PresetCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Task 1: the 52-preset catalog + rail counts, locked verbatim against the desktop. */
class TranscodePresetsTest {

    @Test fun catalogHasExactly52Presets() {
        assertEquals(52, TranscodePresets.all.size)
    }

    @Test fun everyIdIsUnique() {
        val ids = TranscodePresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun categoryCountsMatchTheSpec() {
        // Web 20, Edit 6, Social 10, Audio 5, Devices 7, Legacy 2, Image 2 (Σ 52)
        assertEquals(20, PresetRail.count(PresetCategory.WEB))
        assertEquals(6, PresetRail.count(PresetCategory.EDIT))
        assertEquals(10, PresetRail.count(PresetCategory.SOCIAL))
        assertEquals(5, PresetRail.count(PresetCategory.AUDIO))
        assertEquals(7, PresetRail.count(PresetCategory.DEVICES))
        assertEquals(2, PresetRail.count(PresetCategory.LEGACY))
        assertEquals(2, PresetRail.count(PresetCategory.IMAGE))
        // The seven category counts sum to the whole catalog.
        assertEquals(52, PresetCategory.entries.sumOf { PresetRail.count(it) })
    }

    @Test fun railIsAllPlusSevenCategoriesPlusCustom() {
        val keys = PresetRail.entries.map { it.key }
        assertEquals(listOf("all", "web", "edit", "social", "audio", "devices", "legacy", "image", "custom"), keys)
        assertEquals(52, PresetRail.entries.first { it.key == "all" }.count)
        assertEquals(0, PresetRail.entries.first { it.key == "custom" }.count)
    }

    @Test fun webmVp9IsThe1080pVp9Preset_notAnIdCalledVp9_1080() {
        // Gotcha: the 1080p VP9 preset's id is `webm-vp9`, there is no `vp9-1080`.
        val p = TranscodePresets.find("webm-vp9")!!
        assertEquals("VP9 · 1080p", p.name)
        assertEquals("webm", p.ext)
        assertNull(TranscodePresets.find("vp9-1080"))
    }

    @Test fun displayStringsAndExtAreVerbatim() {
        val h265 = TranscodePresets.find("h265-1080")!!
        assertEquals("H.265 / HEVC · 1080p", h265.name)
        assertEquals("mp4 · 5 Mbps · CRF 23", h265.sub)
        assertEquals("Smaller files", h265.badge)

        val ig = TranscodePresets.find("ig-vert")!!
        assertEquals("1080×1920 · 12 Mbps", ig.sub) // U+00D7 multiplication sign

        // ext drives output filename — spot-check the non-mp4 families.
        assertEquals("webm", TranscodePresets.find("av1-1080")!!.ext)
        assertEquals("mov", TranscodePresets.find("prores-4444")!!.ext)
        assertEquals("mxf", TranscodePresets.find("dnxhr-hq")!!.ext)
        assertEquals("mpg", TranscodePresets.find("dvd-ntsc")!!.ext)
        assertEquals("gif", TranscodePresets.find("gif")!!.ext)
        assertEquals("webp", TranscodePresets.find("webp-anim")!!.ext)
    }

    @Test fun badgesArePresentWhereSpecified_absentOtherwise() {
        assertEquals("Preserve 4K", TranscodePresets.find("h264-source")!!.badge)
        assertEquals("Most compatible", TranscodePresets.find("h264-1080")!!.badge)
        assertEquals("Best quality", TranscodePresets.find("yt-source")!!.badge)
        assertEquals("Smallest", TranscodePresets.find("opus-160")!!.badge)
        assertNull(TranscodePresets.find("h264-720")!!.badge)
        assertNull(TranscodePresets.find("twitter")!!.badge)
    }

    @Test fun findReturnsNullForUnknownAndCustomIds() {
        assertNull(TranscodePresets.find("custom-abc123"))
        assertNull(TranscodePresets.find("nope"))
    }
}
