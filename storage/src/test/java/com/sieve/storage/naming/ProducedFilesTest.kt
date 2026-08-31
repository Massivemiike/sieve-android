package com.sieve.storage.naming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProducedFilesTest {

    @Test fun `scratch predicate matches yt-dlp temporaries`() {
        assertTrue(ProducedFiles.isScratch("video.mp4.part"))
        assertTrue(ProducedFiles.isScratch("video.f137.mp4"))
        assertTrue(ProducedFiles.isScratch("video.ytdl"))
        assertTrue(ProducedFiles.isScratch("video.part-Frag12.webm"))
        assertTrue(ProducedFiles.isScratch("thumb.temp"))
        assertTrue(ProducedFiles.isScratch("x.temp.jpg"))
    }

    @Test fun `real outputs are not scratch`() {
        assertTrue(!ProducedFiles.isScratch("video.mp4"))
        assertTrue(!ProducedFiles.isScratch("video.en.srt"))
        assertTrue(!ProducedFiles.isScratch("video.info.json"))
    }

    @Test fun `stem strips known double-ext sidecar suffixes`() {
        assertEquals("video", ProducedFiles.stemOf("video.mp4"))
        assertEquals("video", ProducedFiles.stemOf("video.en.srt"))
        assertEquals("video", ProducedFiles.stemOf("video.info.json"))
    }

    @Test fun `classify picks the video container as primary`() {
        val names = listOf("video.info.json", "video.en.srt", "video.mp4", "video.jpg", "video.f137.m4a")
        val c = ProducedFiles.classify(names)!!
        assertEquals("video.mp4", c.primary)
        assertEquals(listOf("video.en.srt", "video.info.json", "video.jpg"), c.sidecars.sorted())
        assertTrue("video.f137.m4a" !in c.sidecars) // scratch dropped
    }

    @Test fun `audio-only download picks the audio file`() {
        val names = listOf("song.info.json", "song.opus", "song.jpg")
        val c = ProducedFiles.classify(names)!!
        assertEquals("song.opus", c.primary)
    }

    @Test fun `primary hint wins over extension priority`() {
        val names = listOf("a.mp4", "b.mkv")
        val c = ProducedFiles.classify(names, primaryHint = "b.mkv")!!
        assertEquals("b.mkv", c.primary)
        assertEquals(listOf("a.mp4"), c.sidecars)
    }

    @Test fun `empty or all-scratch input returns null`() {
        assertNull(ProducedFiles.classify(emptyList()))
        assertNull(ProducedFiles.classify(listOf("x.part", "y.ytdl")))
    }
}
