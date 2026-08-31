package com.sieve.storage.naming

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class MimeMapperTest {

    @Test fun `extension extraction is lowercase and dotless`() {
        assertEquals("mp4", MimeMapper.extensionOf("Clip.MP4"))
        assertEquals("srt", MimeMapper.extensionOf("Title [id].en.SRT"))
        assertEquals("", MimeMapper.extensionOf("noext"))
    }

    @Test fun `extension extraction survives spaces and hashes in the name`() {
        assertEquals("mp4", MimeMapper.extensionOf("a b # c.mp4"))
    }

    @Test fun `override table covers formats the framework misses`() {
        assertEquals("video/x-matroska", MimeMapper.mimeOf("a.mkv"))
        assertEquals("audio/ogg", MimeMapper.mimeOf("a.opus"))
        assertEquals("text/vtt", MimeMapper.mimeOf("a.en.vtt"))
    }

    @Test fun `common types resolve`() {
        assertEquals("video/mp4", MimeMapper.mimeOf("a.mp4"))
    }

    @Test fun `unknown extension falls back to octet-stream`() {
        assertEquals("application/octet-stream", MimeMapper.mimeOf("a.zzz"))
        assertEquals("application/octet-stream", MimeMapper.mimeOf("noext"))
    }
}
