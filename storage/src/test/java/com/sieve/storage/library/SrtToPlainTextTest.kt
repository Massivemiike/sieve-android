package com.sieve.storage.library

import kotlin.test.Test
import kotlin.test.assertEquals

class SrtToPlainTextTest {
    @Test fun `strips cue numbers, timecodes, and tags`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            <i>Hello</i> {\an8}world

            2
            00:00:05,000 --> 00:00:07,000
            Second line
        """.trimIndent()
        assertEquals("Hello world\nSecond line", SrtToPlainText.convert(srt))
    }

    @Test fun `drops WEBVTT header and NOTE blocks`() {
        val vtt = """
            WEBVTT

            NOTE this is a note

            00:00:01.000 --> 00:00:02.000
            Only this
        """.trimIndent()
        assertEquals("Only this", SrtToPlainText.convert(vtt))
    }

    @Test fun `collapses consecutive blank lines`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nA\n\n\n\n2\n00:00:03,000 --> 00:00:04,000\nB"
        assertEquals("A\nB", SrtToPlainText.convert(srt))
    }
}
