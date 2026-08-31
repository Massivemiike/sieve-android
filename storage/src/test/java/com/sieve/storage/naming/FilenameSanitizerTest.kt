package com.sieve.storage.naming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilenameSanitizerTest {

    @Test fun `illegal characters become underscore`() {
        assertEquals("AC_DC_ Live_.mp4", FilenameSanitizer.sanitize("AC/DC: Live?.mp4"))
    }

    @Test fun `control chars are stripped`() {
        val input = "a" + 0.toChar() + 7.toChar() + "b.txt" // NUL + BEL between a and b
        assertEquals("a_b.txt", FilenameSanitizer.sanitize(input))
    }

    @Test fun `trailing dots and spaces trimmed but extension preserved`() {
        assertEquals("video.mp4", FilenameSanitizer.sanitize("video.mp4.  "))
        assertEquals("name", FilenameSanitizer.sanitize("name...   "))
    }

    @Test fun `windows reserved stem is escaped`() {
        assertEquals("_CON.txt", FilenameSanitizer.sanitize("CON.txt"))
        assertEquals("_nul", FilenameSanitizer.sanitize("nul"))
        assertEquals("console.txt", FilenameSanitizer.sanitize("console.txt"))
    }

    @Test fun `empty or all-illegal name falls back to underscore`() {
        assertEquals("_", FilenameSanitizer.sanitize(""))
        assertEquals("_", FilenameSanitizer.sanitize("///"))
    }

    @Test fun `byte length is capped without splitting a multibyte codepoint`() {
        val emoji = "😀" // 😀, 4 UTF-8 bytes
        val long = emoji.repeat(100) + ".mp4"
        val out = FilenameSanitizer.sanitize(long, maxBytes = 20)
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 20, "over cap: $out")
        assertTrue(out.endsWith(".mp4"), "extension dropped: $out")
        assertTrue(!out.contains(0xFFFD.toChar())) // no U+FFFD from a split
    }

    @Test fun `extension longer than cap still yields a valid short name`() {
        val out = FilenameSanitizer.sanitize("x".repeat(50) + ".mp4", maxBytes = 8)
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 8)
        assertTrue(out.isNotEmpty())
    }
}
