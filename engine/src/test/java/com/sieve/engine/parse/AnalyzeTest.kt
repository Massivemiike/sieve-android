package com.sieve.engine.parse

import com.sieve.engine.model.VideoFormat
import com.sieve.engine.model.VideoInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyzeParserTest {
    @Test fun emptyFormatsArrayPasses() =
        assertTrue(AnalyzeParser.parse("""{"formats":[]}""").formats.isEmpty())

    @Test fun emptyObjectFails() { assertFailsWith<AnalyzeException> { AnalyzeParser.parse("{}") } }

    @Test fun blankThrows() { assertFailsWith<AnalyzeException> { AnalyzeParser.parse("   ") } }

    @Test fun idOnlyPasses() = assertEquals("x", AnalyzeParser.parse("""{"id":"x"}""").id)

    @Test fun badJsonThrows() { assertFailsWith<AnalyzeException> { AnalyzeParser.parse("{not json") } }
}

class AnalyzeErrorTest {
    @Test fun lastErrorLineWins() =
        assertEquals("ERROR: last", AnalyzeError.extract("ERROR: first\nnoise\nERROR: last", 1))

    @Test fun codeFallback() = assertEquals("yt-dlp exited with code 2", AnalyzeError.extract("   \n  ", 2))
}

class StoryboardDetectorTest {
    @Test fun emptyIsTrue() = assertTrue(StoryboardDetector.hasOnlyStoryboards(VideoInfo()))

    @Test fun realVideoIsFalse() = assertFalse(
        StoryboardDetector.hasOnlyStoryboards(
            VideoInfo(formats = listOf(VideoFormat("22", vcodec = "avc1", acodec = "mp4a"))),
        ),
    )

    @Test fun allStoryboardsIsTrue() = assertTrue(
        StoryboardDetector.hasOnlyStoryboards(
            VideoInfo(formats = listOf(VideoFormat("sb0", formatNote = "storyboard"))),
        ),
    )
}
