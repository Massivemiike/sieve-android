package com.sieve.app.di

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * The full-file copies of picked transcode sources. A native ffmpeg cannot open a `content://` URI, so a
 * picked file is copied into [dir] (the app cache) as `tx-src-<n>.<ext>` and that path is what the job
 * reads. Nothing else deletes these, so this class owns their whole life: [release] when the job that
 * needs one is done, [sweep] at startup for copies nothing references any more.
 *
 * Only a direct child of [dir] named `tx-src-*` is ever deleted, so a path read back from a persisted
 * spec can never make this remove anything else.
 */
class SourceCopies(private val dir: File) {

    /**
     * Copies [open]'s stream into a new `tx-src-*` file and returns its path. A failed or cancelled copy
     * (a multi-GB file, the screen closed mid-copy) deletes its partial file. Cancellation is checked
     * per chunk, so a copy does not run on to the end after its caller is gone.
     */
    suspend fun materialize(name: String, open: () -> InputStream?): String = withContext(Dispatchers.IO) {
        val dst = File(dir, "$PREFIX${System.nanoTime()}.${extensionOf(name)}")
        try {
            val input = requireNotNull(open()) { "cannot open source $name" }
            input.use { src ->
                dst.outputStream().use { out ->
                    val buf = ByteArray(COPY_CHUNK)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        ensureActive()
                    }
                }
            }
        } catch (t: Throwable) {
            dst.delete()
            throw t
        }
        dst.absolutePath
    }

    /** Deletes the copy at [path]. False (and nothing touched) when it is not one of ours or already gone. */
    fun release(path: String): Boolean {
        val f = File(path)
        return isManaged(f) && f.delete()
    }

    /**
     * Deletes every `tx-src-*` copy whose path is not in [inUse] — copies orphaned by a process death
     * mid-job, or left by app versions that never cleaned up. Returns how many were deleted.
     */
    fun sweep(inUse: Set<String>): Int {
        val keep = inUse.mapTo(HashSet()) { runCatching { File(it).canonicalPath }.getOrDefault(it) }
        return dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }.orEmpty()
            .count { f -> f.canonicalPath !in keep && f.delete() }
    }

    private fun isManaged(f: File): Boolean =
        f.name.startsWith(PREFIX) && runCatching { f.canonicalFile.parentFile == dir.canonicalFile }.getOrDefault(false)

    /** The extension only hints ffmpeg's demuxer; keep it short and filename-safe whatever the display name was. */
    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) } ?: "mp4"

    companion object {
        const val PREFIX = "tx-src-"
        private const val COPY_CHUNK = 256 * 1024
    }
}
