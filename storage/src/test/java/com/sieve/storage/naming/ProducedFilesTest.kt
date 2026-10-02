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

    @Test fun `hasMedia needs a finished video or audio file`() {
        assertTrue(ProducedFiles.hasMedia(listOf("a.mp4", "a.info.json")))
        assertTrue(ProducedFiles.hasMedia(listOf("Song.OPUS", "Song.jpg")))
        assertTrue(!ProducedFiles.hasMedia(emptyList()))
        assertTrue(!ProducedFiles.hasMedia(listOf("a.mp4.part", "a.f137.mp4", "a.temp.mp4", "a.ytdl")))   // scratch only
        assertTrue(!ProducedFiles.hasMedia(listOf("a.jpg", "a.webp", "a.en.vtt", "a.srt", "a.info.json")))  // sidecars only
    }

    @Test fun `unmerged streams with non-numeric format ids are scratch`() {
        // yt-dlp names each stream of a not-yet-merged download "<name>.f<format_id>.<ext>"; ids are not always numeric.
        assertTrue(ProducedFiles.isScratch("Song [abc].f251-drc.webm"))
        assertTrue(ProducedFiles.isScratch("Clip [x9].fhls-720p.mp4"))
        assertTrue(ProducedFiles.isScratch("Reel [1195289147628387].f1415483346858418v.mp4"))
        assertTrue(ProducedFiles.isScratch("Post [7151].fdash-video_1.mp4"))
        assertTrue(!ProducedFiles.hasMedia(listOf("A [1].f251-drc.webm", "A [1].fhls-720p.mp4", "A [1].jpg")))
        // ...but a subtitle language starting with "f" (French, Finnish, Persian) is a real sidecar, and a merged file is media.
        assertTrue(!ProducedFiles.isScratch("Talk [id].fr.vtt"))
        assertTrue(!ProducedFiles.isScratch("Talk [id].fa.srt"))
        assertTrue(!ProducedFiles.isScratch("Me at the zoo [jNQXAC9IVRw].mp4"))
        assertTrue(ProducedFiles.hasMedia(listOf("A [1].mp4", "A [1].f251-drc.webm")))
    }
}
