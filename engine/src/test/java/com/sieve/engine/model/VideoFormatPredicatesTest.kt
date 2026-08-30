package com.sieve.engine.model

import com.sieve.engine.parse.analyzeJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoFormatPredicatesTest {
    private fun fmt(v: String?, a: String?) = VideoFormat(formatId = "f", vcodec = v, acodec = a)

    @Test fun audioOnly() {
        val f = fmt("none", "opus")
        assertTrue(f.isAudioOnly)
        assertNull(f.videoCodecFamily)
        assertEquals("opus", f.audioCodecFamily)
    }

    @Test fun codecFamilyStripsDots() =
        assertEquals("av01", fmt("av01.0.08M.08", "none").videoCodecFamily)

    @Test fun bigFilesizeNoOverflow() {
        val f = analyzeJson.decodeFromString<VideoFormat>("""{"format_id":"137","filesize":3221225472}""")
        assertEquals(3_221_225_472L, f.filesize)
    }

    @Test fun transientCookieFallbackIgnored() {
        val i = analyzeJson.decodeFromString<VideoInfo>("""{"id":"x","_cookieFallback":true}""")
        assertFalse(i.cookieFallback)
    }

    @Test fun emptyFormatsArrayDecodes() =
        assertTrue(analyzeJson.decodeFromString<VideoInfo>("""{"formats":[]}""").formats.isEmpty())

    @Test fun bestSizePrefersRealOverApprox() {
        assertEquals(10L, VideoFormat("f", filesize = 10, filesizeApprox = 99).bestSizeBytes)
        assertEquals(99L, VideoFormat("f", filesize = 0, filesizeApprox = 99).bestSizeBytes)
    }
}
