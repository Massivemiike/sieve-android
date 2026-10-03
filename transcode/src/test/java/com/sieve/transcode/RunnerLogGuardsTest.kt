package com.sieve.transcode

import com.sieve.transcode.runner.FfmpegProgress
import com.sieve.transcode.runner.ProgressWatch
import com.sieve.transcode.runner.StderrLog
import com.sieve.transcode.runner.TranscodeEvent
import com.sieve.transcode.runner.android.BoundedLineReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader

/** The two bounds that keep a stderr flood from growing without limit: the line reader and [StderrLog]. */
class RunnerLogGuardsTest {

    // --- BoundedLineReader: readLine() semantics, with a ceiling ------------------------------------------------------

    private fun lines(reader: Reader, max: Int = 100): List<String> {
        val r = BoundedLineReader(reader, max)
        return generateSequence { r.readLine() }.toList()
    }

    @Test fun `all three line ends are line ends and blank lines survive`() {
        assertEquals(listOf("a", "b", "c", "", "d"), lines(StringReader("a\nb\r\nc\r\n\rd")))
        assertEquals(listOf("frame=1", "frame=2"), lines(StringReader("frame=1\rframe=2\r")))
        assertEquals(emptyList<String>(), lines(StringReader("")))
        assertEquals(listOf(""), lines(StringReader("\n")))
    }

    @Test fun `a last line without a terminator is returned`() {
        assertEquals(listOf("a", "tail"), lines(StringReader("a\ntail")))
    }

    /** One char per read, so a CR and its LF arrive in different reads. */
    private class Trickle(private val text: String) : Reader() {
        private var i = 0
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (i >= text.length) return -1
            cbuf[off] = text[i++]
            return 1
        }
        override fun close() {}
    }

    @Test fun `a CR LF pair split across two reads is still one line end`() {
        assertEquals(listOf("a", "b"), lines(Trickle("a\r\nb\n")))
    }

    @Test fun `a line longer than the ceiling is cut, and the rest of it is dropped not carried over`() {
        assertEquals(listOf("abcde", "next"), lines(StringReader("abcdefghijklmnop\nnext\n"), max = 5))
        assertEquals(listOf("abcde"), lines(StringReader("a".repeat(1_000_000).replaceRange(0, 5, "abcde")), max = 5))
    }

    // --- StderrLog -----------------------------------------------------------------------------------------------------

    private class Sink {
        val events = mutableListOf<TranscodeEvent.Log>()
        val emit: (TranscodeEvent.Log) -> Unit = { events += it }
        val lines get() = events.map { it.line }
    }

    @Test fun `identical consecutive lines are counted, not repeated, and the count is reported when the stream moves on`() {
        val log = StderrLog()
        val sink = Sink()
        repeat(5) { log.onLine("same", sink.emit) }
        log.onLine("other", sink.emit)
        log.onLine("other", sink.emit)
        log.finish(sink.emit)
        assertEquals(listOf("same", "Last message repeated 4 more times", "other", "Last message repeated 1 more time"), sink.lines)
    }

    @Test fun `a line that comes back after another one is a new line`() {
        val log = StderrLog()
        val sink = Sink()
        for (l in listOf("a", "b", "a", "b")) log.onLine(l, sink.emit)
        assertEquals(listOf("a", "b", "a", "b"), sink.lines)
    }

    // The RC spawns ffmpeg without -nostats and filters no stats line, so ffmpeg's periodic progress line is a line like any other
    // here (it never repeats, so it is neither collapsed nor, at about two a second, paced away).
    @Test fun `ANSI is stripped and a stats line is an ordinary line`() {
        val log = StderrLog()
        val sink = Sink()
        val stats = "frame=  429 fps= 60 q=28.0 size=    1024KiB time=00:00:14.30 bitrate=1403.9kbits/s speed=2.0x"
        log.onLine(stats, sink.emit)
        log.onLine("\u001B[31mConversion failed!\u001B[0m", sink.emit)
        assertEquals(listOf(stats, "Conversion failed!"), sink.lines)
        assertTrue(sink.events.last().isError)
        assertEquals("$stats\nConversion failed!\n", log.tail())
    }

    @Test fun `a burst passes, then lines are paced and the skipped ones are counted`() {
        var now = 0L
        val log = StderrLog(nowNanos = { now }, burst = 10, perSecond = 2)
        val sink = Sink()
        for (i in 1..100) log.onLine("line $i", sink.emit)
        assertEquals((1..10).map { "line $it" }, sink.lines)

        now += 1_000_000_000L // one second: two more tokens
        log.onLine("line 101", sink.emit)
        assertEquals("90 log lines skipped (too many, too fast)", sink.lines[10])
        assertEquals("line 101", sink.lines[11])
        log.onLine("line 102", sink.emit)
        log.onLine("line 103", sink.emit) // out of tokens again
        log.finish(sink.emit)
        assertEquals(listOf("line 102", "1 log line skipped (too many, too fast)"), sink.lines.drop(12))
    }

    @Test fun `the tail and the last lines stay bounded however much is written`() {
        val log = StderrLog(nowNanos = { 0L }, burst = Int.MAX_VALUE, perSecond = 0)
        val sink = Sink()
        for (i in 1..50_000) log.onLine("distinct line number $i with some padding to make it long enough ....................", sink.emit)
        assertTrue(log.tail().length <= 65_536)
        assertEquals(30, log.lastLines().size)
        assertEquals("distinct line number 50000 with some padding to make it long enough ....................", log.lastLines().last())
    }

    @Test fun `a very long single line is cut at 500 characters`() {
        val log = StderrLog()
        val sink = Sink()
        log.onLine("x".repeat(10_000), sink.emit)
        assertEquals(500, sink.lines.single().length)
    }

    // --- ProgressWatch -------------------------------------------------------------------------------------------------

    private fun p(out: Long = 0, frame: Long? = null, size: Long? = null, end: Boolean = false) =
        FfmpegProgress(outTimeUs = out, percent = null, speed = null, speedRaw = null, frame = frame, totalSize = size, isEnd = end)

    @Test fun `only an advance moves the watch`() {
        val w = ProgressWatch()
        val v0 = w.version
        w.observe(p(out = 0, frame = 0, size = 0)) // ffmpeg's start-up block
        w.observe(p(out = 0, frame = 0, size = 0))
        assertEquals(v0, w.version)

        w.observe(p(out = 1_000))
        val v1 = w.version
        assertTrue(v1 != v0)
        w.observe(p(out = 1_000)) // the same again
        w.observe(p(out = 900)) // backwards
        assertEquals(v1, w.version)

        w.observe(p(out = 1_000, frame = 5))
        assertTrue(w.version != v1)
    }

    @Test fun `frame count, output size and the end block each count on their own`() {
        for (block in listOf(p(frame = 1), p(size = 10), p(end = true))) {
            val w = ProgressWatch()
            val v = w.version
            w.observe(block)
            assertFalse(block.toString(), w.version == v)
        }
    }

    @Test fun `the output size moves the watch but is not media progress`() {
        val w = ProgressWatch()
        assertFalse(w.mediaAdvanced)
        w.observe(p(out = 0, frame = 0, size = 48)) // ffmpeg 8.1 with the encoder open: the muxer header is written, nothing is encoded yet
        assertTrue("it is an advance for the two-minute watchdog", w.version != 0L)
        assertFalse(w.mediaAdvanced)
        w.observe(p(out = 0, frame = 0, size = 4_096)) // more bytes, still no frame
        assertFalse(w.mediaAdvanced)
        w.observe(p(out = 0, frame = null, size = null))
        assertFalse(w.mediaAdvanced)
    }

    @Test fun `an out_time, a frame count or the end block is media progress, each on its own, and it stays`() {
        for (block in listOf(p(out = 1), p(frame = 1), p(end = true))) {
            val w = ProgressWatch()
            assertFalse(w.mediaAdvanced)
            w.observe(block)
            assertTrue(block.toString(), w.mediaAdvanced)
            w.observe(p(out = 0, frame = 0, size = 48)) // a later start-up-looking block does not take it back
            assertTrue(block.toString(), w.mediaAdvanced)
        }
    }
}
