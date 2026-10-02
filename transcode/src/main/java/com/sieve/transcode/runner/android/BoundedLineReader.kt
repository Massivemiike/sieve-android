package com.sieve.transcode.runner.android

import java.io.Reader

/**
 * `BufferedReader.readLine()` with a ceiling on the line length. A flood that never ends a line (a wedged codec
 * logging in a loop, binary noise) would otherwise grow one string without bound before it ever reached the runner's
 * own caps. Whatever is past [maxChars] in one line is read and dropped, so the stream keeps draining and the pipe
 * never backs up into ffmpeg.
 *
 * Line ends are the same three `readLine()` knows: `\n`, `\r` and `\r\n` (ffmpeg ends its stats lines with a bare `\r`).
 * Blank lines are returned as "". A final line without a terminator is returned; then null at end of stream.
 */
internal class BoundedLineReader(private val reader: Reader, private val maxChars: Int) {
    private val buf = CharArray(4096)
    private var pos = 0
    private var lim = 0
    private var skipLf = false

    fun readLine(): String? {
        val line = StringBuilder()
        var sawAny = false
        while (true) {
            if (pos >= lim) {
                val n = reader.read(buf, 0, buf.size)
                if (n < 0) return if (sawAny) line.toString() else null
                pos = 0
                lim = n
                if (n == 0) continue
            }
            val c = buf[pos++]
            if (skipLf) {
                skipLf = false
                if (c == '\n') continue // the LF of a CR LF pair already ended the previous line
            }
            when (c) {
                '\n' -> return line.toString()
                '\r' -> { skipLf = true; return line.toString() }
                else -> {
                    sawAny = true
                    if (line.length < maxChars) line.append(c)
                }
            }
        }
    }
}
