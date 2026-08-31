package com.sieve.storage.sink

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.sieve.storage.naming.StoragePaths
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** API 29+ sink into the Downloads collection under `Download/Sieve[/label]` with IS_PENDING publish. */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreDownloadsSink(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DestinationSink {

    override val rootLabel: String get() = "Downloads/Sieve"

    private val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    override suspend fun existingNames(dirLabel: String?): Set<String> = withContext(io) {
        val rel = StoragePaths.outputRelativePath(dirLabel) + "/"
        val names = linkedSetOf<String>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(rel),
            null,
        )?.use { c -> while (c.moveToNext()) names += c.getString(0) }
        names
    }

    override suspend fun write(dirLabel: String?, name: String, mime: String, bytes: InputStream): OutputTarget =
        withContext(io) {
            val rel = StoragePaths.outputRelativePath(dirLabel) // "Download/Sieve[/label]"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, rel)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val item = context.contentResolver.insert(collection, values) ?: error("MediaStore insert failed for $name")
            try {
                context.contentResolver.openOutputStream(item, "wt").use { out ->
                    requireNotNull(out) { "null output stream" }
                    bytes.copyTo(out, 64 * 1024)
                }
            } catch (t: Throwable) {
                context.contentResolver.delete(item, null, null)
                throw t
            }
            val disp = if (dirLabel.isNullOrBlank()) "Sieve/$name" else "Sieve/$dirLabel/$name"
            OutputTarget(name = name, uri = item.toString(), relativeDisplay = disp)
        }

    override suspend fun commit(target: OutputTarget) {
        withContext(io) {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            context.contentResolver.update(Uri.parse(target.uri), values, null, null)
        }
    }

    override suspend fun deletePending(target: OutputTarget) {
        withContext(io) { runCatching { context.contentResolver.delete(Uri.parse(target.uri), null, null) } }
    }
}
