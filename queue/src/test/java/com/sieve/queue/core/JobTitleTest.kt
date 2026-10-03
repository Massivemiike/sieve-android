package com.sieve.queue.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a queue row calls itself: its title, else the link (a download) or the input file (a transcode), never a bare "Download". */
class JobTitleTest {
    private fun download(url: String, title: String = "") =
        QueueJob("a", JobSpec.Download(url, listOf("-f", "best")), OutputRequest("d", "o"), title = title)

    private fun transcode(input: String, title: String = "") =
        QueueJob("t", JobSpec.Transcode(input, listOf("-c:v", "h264"), 10.0, false), OutputRequest("d", "o.mp4"), title = title)

    @Test fun `a row with a title shows it`() {
        assertEquals("My Vid", download("https://youtube.com/watch?v=abc", title = "My Vid").displayTitle)
        assertEquals("clip-720p.mp4", transcode("/sdcard/Movies/clip.mkv", title = "clip-720p.mp4").displayTitle)
    }

    @Test fun `a download without a title shows its link`() {
        val link = "https://soundcloud.com/ethmusic/lostin-powers-she-so-heavy"
        assertEquals(link, download(link).displayTitle)
        assertEquals(link, download(" $link\n").displayTitle)
        assertEquals(link, download(link, title = "   ").displayTitle)
    }

    @Test fun `a transcode without a title shows its input file`() {
        assertEquals("clip.mkv", transcode("/sdcard/Movies/clip.mkv").displayTitle)
    }

    @Test fun `with nothing else to show the row is named for its kind`() {
        assertEquals("Download", download("").displayTitle)
        assertEquals("Download", download("  ").displayTitle)
        assertEquals("Transcode", transcode("").displayTitle)
        assertEquals("Transcode", transcode("/sdcard/Movies/").displayTitle)
    }
}
