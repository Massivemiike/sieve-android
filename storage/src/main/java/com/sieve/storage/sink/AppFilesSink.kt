package com.sieve.storage.sink

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Last-resort sink under `filesDir/output/Sieve[/label]`. Returns a `file://` URI (a content://
 * FileProvider URI for sharing is an :app concern and lands with the UI module).
 */
class AppFilesSink(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DestinationSink {

    override val rootLabel: String get() = "App files"

    private fun dir(dirLabel: String?): File {
        val base = File(context.filesDir, "output/Sieve")
        val d = if (dirLabel.isNullOrBlank()) base else File(base, dirLabel)
        d.mkdirs()
        return d
    }

    override suspend fun existingNames(dirLabel: String?): Set<String> = withContext(io) {
        dir(dirLabel).listFiles()?.map { it.name }?.toSet() ?: emptySet()
    }

    override suspend fun write(dirLabel: String?, name: String, mime: String, bytes: InputStream): OutputTarget =
        withContext(io) {
            val out = File(dir(dirLabel), name)
            out.outputStream().use { bytes.copyTo(it, 64 * 1024) }
            val disp = if (dirLabel.isNullOrBlank()) name else "$dirLabel/$name"
            OutputTarget(name = name, uri = Uri.fromFile(out).toString(), relativeDisplay = disp)
        }

    override suspend fun commit(target: OutputTarget) {}

    override suspend fun deletePending(target: OutputTarget) {
        withContext(io) {
            File(context.filesDir, "output/Sieve").walkTopDown()
                .firstOrNull { it.isFile && it.name == target.name }?.delete()
        }
    }
}
