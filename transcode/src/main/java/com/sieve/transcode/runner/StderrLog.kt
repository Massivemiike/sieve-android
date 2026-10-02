package com.sieve.transcode.runner

/**
 * What the runner keeps of ffmpeg's stderr: the log events, the 64 KB tail the error summary is built from and the last
 * 30 lines the failed row shows. All three are bounded however much ffmpeg (or a codec under it) writes.
 *
 * A wedged MediaCodec can make ffmpeg repeat the same complaint a thousand times a second. Left alone that is a Room write
 * per line in the queue, a tail with nothing in it but the complaint and a pipe that backs up into ffmpeg itself.
 * So: a line equal to the one before it is only counted ("Last message repeated N more times"), and the lines that
 * still get through are paced by a token bucket ([burst] lines at once, then [perSecond] a second; the rest is counted and
 * reported as "N log lines skipped"). A normal run never gets near either limit: ffmpeg's banner, stream map and the
 * encoder's closing statistics are a hundred lines or so, its periodic `frame=... time=... speed=...` stats line arrives
 * about twice a second and never repeats itself.
 *
 * Called from the stderr reader; [finish] and the readers of the tails run on the run's own coroutine, hence the locking.
 */
internal class StderrLog(
    private val nowNanos: () -> Long = System::nanoTime,
    private val burst: Int = FfmpegRunner.LOG_BURST_LINES,
    private val perSecond: Int = FfmpegRunner.LOG_LINES_PER_SECOND,
) {
    private val tail = StringBuilder()
    private val lastLines = ArrayDeque<String>()
    private var previous: String? = null
    private var repeats = 0
    private var dropped = 0
    private var tokens = burst.toDouble()
    private var refilledAt = nowNanos()

    @Synchronized
    fun onLine(raw: String, emit: (TranscodeEvent.Log) -> Unit) {
        val clean = FfmpegRunner.ANSI.replace(raw, "").take(MAX_LINE)
        if (clean == previous) {
            repeats++
            return
        }
        flushRepeats(emit)
        previous = clean
        refill()
        if (tokens < 1.0) {
            dropped++
            return
        }
        flushDropped(emit)
        tokens -= 1.0
        emit(record(clean))
    }

    /** Reports what is still being held back (a repeat count, skipped lines) once the stream has ended. */
    @Synchronized
    fun finish(emit: (TranscodeEvent.Log) -> Unit) {
        flushRepeats(emit)
        flushDropped(emit)
    }

    @Synchronized fun tail(): String = tail.toString()

    @Synchronized fun lastLines(): List<String> = lastLines.toList()

    private fun flushRepeats(emit: (TranscodeEvent.Log) -> Unit) {
        if (repeats == 0) return
        emit(record("Last message repeated $repeats more time${if (repeats == 1) "" else "s"}"))
        repeats = 0
    }

    private fun flushDropped(emit: (TranscodeEvent.Log) -> Unit) {
        if (dropped == 0) return
        emit(record("$dropped log line${if (dropped == 1) "" else "s"} skipped (too many, too fast)"))
        dropped = 0
    }

    private fun refill() {
        val now = nowNanos()
        val earned = (now - refilledAt) / 1e9 * perSecond
        if (earned > 0) {
            tokens = minOf(burst.toDouble(), tokens + earned)
            refilledAt = now
        }
    }

    private fun record(line: String): TranscodeEvent.Log {
        tail.append(line).append('\n')
        if (tail.length > FfmpegRunner.STDERR_MAX) tail.delete(0, tail.length - FfmpegRunner.STDERR_MAX)
        lastLines.addLast(line)
        while (lastLines.size > LAST_LINES) lastLines.removeFirst()
        return TranscodeEvent.Log(line, FfmpegRunner.ERROR_LINE.containsMatchIn(line))
    }

    private companion object {
        const val MAX_LINE = 500
        const val LAST_LINES = 30
    }
}
