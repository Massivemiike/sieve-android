package com.sieve.engine.update

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Reads the version of a yt-dlp zipapp (the one `yt-dlp` file youtubedl-android runs) WITHOUT running it.
 * Starting Python to ask `yt-dlp --version` costs seconds on a phone; this reads about 170 KB.
 *
 * The file is `#!/usr/bin/env python3\n` followed by a zip whose offsets are relative to the zip's own start
 * (yt-dlp's Makefile `cat`s the two together). `java.util.zip.ZipFile` on Android does not take that prefix on
 * every release, so the central directory is read by hand: the prefix length is whatever lies between the end of
 * the central directory and the end-of-central-directory record (0 for a plain zip, 23 for yt-dlp's).
 * The library keeps no version file next to the binary, so the zipapp's own `yt_dlp/version.py` is the only
 * record of what will actually run.
 */
object YtDlpZipapp {
    private const val VERSION_ENTRY = "yt_dlp/version.py"
    private val VERSION = Regex("""(?m)^__version__\s*=\s*['"]([^'"\r\n]+)['"]""")

    private const val EOCD_SIG = 0x06054b50
    private const val CEN_SIG = 0x02014b50
    private const val LOC_SIG = 0x04034b50
    private const val EOCD_LEN = 22
    private const val CEN_LEN = 46
    private const val LOC_LEN = 30
    private const val MAX_COMMENT = 0xFFFF
    private const val MAX_CEN_BYTES = 8L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 64L * 1024
    private const val STORED = 0
    private const val DEFLATED = 8

    /** `__version__` of the yt-dlp in [file] (e.g. "2025.11.12"), or null when the file is missing or not a readable yt-dlp zipapp. */
    fun version(file: File): String? = try {
        RandomAccessFile(file, "r").use { versionOf(it) }
    } catch (_: IOException) {
        null
    } catch (_: DataFormatException) {
        null
    } catch (_: RuntimeException) {
        null // a corrupt header can index out of range
    }

    private fun versionOf(raf: RandomAccessFile): String? {
        val length = raf.length()
        if (length < EOCD_LEN) return null

        // The end-of-central-directory record sits in the last 22 + comment bytes.
        val tailLen = minOf(length, (EOCD_LEN + MAX_COMMENT).toLong()).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(length - tailLen)
        raf.readFully(tail)
        val t = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var at = tailLen - EOCD_LEN
        while (at >= 0 && !(t.getInt(at) == EOCD_SIG && at + EOCD_LEN + u16(t, at + 20) <= tailLen)) at--
        if (at < 0) return null

        val entries = u16(t, at + 10)
        val cenSize = u32(t, at + 12)
        val cenOffset = u32(t, at + 16)
        if (entries == 0xFFFF || cenSize == 0xFFFFFFFFL || cenOffset == 0xFFFFFFFFL) return null // zip64: not what yt-dlp ships
        val prefix = (length - tailLen + at) - cenSize - cenOffset
        if (prefix < 0 || cenSize > MAX_CEN_BYTES) return null

        val cen = ByteArray(cenSize.toInt())
        raf.seek(prefix + cenOffset)
        raf.readFully(cen)
        val c = ByteBuffer.wrap(cen).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0
        repeat(entries) {
            if (p + CEN_LEN > cen.size || c.getInt(p) != CEN_SIG) return null
            val nameLen = u16(c, p + 28)
            val extraLen = u16(c, p + 30)
            val commentLen = u16(c, p + 32)
            if (p + CEN_LEN + nameLen > cen.size) return null
            if (nameLen == VERSION_ENTRY.length && String(cen, p + CEN_LEN, nameLen, Charsets.UTF_8) == VERSION_ENTRY) {
                return readVersion(
                    raf, at = prefix + u32(c, p + 42), method = u16(c, p + 10),
                    compressedSize = u32(c, p + 20), size = u32(c, p + 24),
                )
            }
            p += CEN_LEN + nameLen + extraLen + commentLen
        }
        return null
    }

    private fun readVersion(raf: RandomAccessFile, at: Long, method: Int, compressedSize: Long, size: Long): String? {
        if (compressedSize > MAX_ENTRY_BYTES || size > MAX_ENTRY_BYTES) return null
        val local = ByteArray(LOC_LEN)
        raf.seek(at)
        raf.readFully(local)
        val l = ByteBuffer.wrap(local).order(ByteOrder.LITTLE_ENDIAN)
        if (l.getInt(0) != LOC_SIG) return null
        val data = ByteArray(compressedSize.toInt())
        raf.seek(at + LOC_LEN + u16(l, 26) + u16(l, 28))
        raf.readFully(data)
        val text = when (method) {
            STORED -> data
            DEFLATED -> inflate(data, size.toInt())
            else -> return null
        }
        return VERSION.find(String(text, Charsets.UTF_8))?.groupValues?.get(1)
    }

    private fun inflate(data: ByteArray, size: Int): ByteArray {
        val inflater = Inflater(true) // zip entries are raw deflate, no zlib header
        try {
            inflater.setInput(data)
            val out = ByteArray(size)
            var n = 0
            while (n < size && !inflater.finished()) {
                val got = inflater.inflate(out, n, size - n)
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += got
            }
            return out.copyOf(n)
        } finally {
            inflater.end()
        }
    }

    private fun u16(b: ByteBuffer, at: Int): Int = b.getShort(at).toInt() and 0xFFFF
    private fun u32(b: ByteBuffer, at: Int): Long = b.getInt(at).toLong() and 0xFFFFFFFFL
}
