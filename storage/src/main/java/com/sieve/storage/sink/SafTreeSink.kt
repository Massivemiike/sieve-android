package com.sieve.storage.sink

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * DestinationSink over a SAF-granted (or file-backed) DocumentFile tree. Overwrite semantics
 * (delete-then-create + `wt` truncation); creates with `application/octet-stream` so the provider
 * never derives/appends an extension. `existingNames` uses `listFiles()` so it works for both a
 * real `tree` URI and a `DocumentFile.fromFile` root.
 */
class SafTreeSink(
    private val context: Context,
    private val treeRoot: DocumentFile,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DestinationSink {

    constructor(context: Context, treeUri: Uri, io: CoroutineDispatcher = Dispatchers.IO) :
        this(context, DocumentFile.fromTreeUri(context, treeUri) ?: error("cannot resolve tree $treeUri"), io)

    override val rootLabel: String get() = treeRoot.name ?: "Folder"

    private fun resolveDir(dirLabel: String?): DocumentFile {
        if (dirLabel.isNullOrBlank()) return treeRoot
        return treeRoot.findFile(dirLabel)?.takeIf { it.isDirectory }
            ?: treeRoot.createDirectory(dirLabel)
            ?: error("cannot create dir $dirLabel")
    }

    override suspend fun existingNames(dirLabel: String?): Set<String> = withContext(io) {
        val dir = if (dirLabel.isNullOrBlank()) treeRoot
        else treeRoot.findFile(dirLabel)?.takeIf { it.isDirectory } ?: return@withContext emptySet()
        dir.listFiles().mapNotNull { it.name }.toSet()
    }

    override suspend fun write(dirLabel: String?, name: String, mime: String, bytes: InputStream): OutputTarget =
        withContext(io) {
            val dir = resolveDir(dirLabel)
            dir.findFile(name)?.delete() // overwrite: avoid "name (1)"
            val doc = dir.createFile("application/octet-stream", name) ?: error("createFile failed for $name")
            context.contentResolver.openOutputStream(doc.uri, "wt").use { out ->
                requireNotNull(out) { "null output stream" }
                bytes.copyTo(out, 64 * 1024)
            }
            val realName = doc.name ?: name
            val disp = if (dirLabel.isNullOrBlank()) realName else "$dirLabel/$realName"
            OutputTarget(name = realName, uri = doc.uri.toString(), relativeDisplay = disp)
        }

    override suspend fun commit(target: OutputTarget) { /* SAF writes are immediate; no pending state */ }

    override suspend fun deletePending(target: OutputTarget) {
        withContext(io) { runCatching { DocumentFile.fromSingleUri(context, Uri.parse(target.uri))?.delete() } }
    }
}
