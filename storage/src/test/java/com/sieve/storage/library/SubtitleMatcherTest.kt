package com.sieve.storage.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleMatcherTest {
    @Test fun `matches plain srt sidecar`() {
        val sibs = listOf("video.mp4", "video.srt", "other.txt")
        assertEquals("video.srt", SubtitleMatcher.findSidecar("video.mp4", sibs))
    }

    @Test fun `matches language-tagged vtt`() {
        val sibs = listOf("clip.mp4", "clip.en.vtt")
        assertEquals("clip.en.vtt", SubtitleMatcher.findSidecar("clip.mp4", sibs))
    }

    @Test fun `prefers srt over vtt when both exist`() {
        val sibs = listOf("m.mp4", "m.vtt", "m.srt")
        assertEquals("m.srt", SubtitleMatcher.findSidecar("m.mp4", sibs))
    }

    @Test fun `returns null when no sidecar`() {
        assertNull(SubtitleMatcher.findSidecar("m.mp4", listOf("m.mp4", "n.srt")))
    }
}
