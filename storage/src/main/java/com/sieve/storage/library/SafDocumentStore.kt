package com.sieve.storage.library

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.sieve.storage.naming.MimeMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real [DocumentStore] over a SAF tree URI. Uses the one-shot `buildChildDocumentsUriUsingTree`
 * query for a fast listing. Requires a real `content://.../tree/...` URI (a `DocumentFile.fromFile`
 * root does NOT work with these DocumentsContract calls — instrumentation needs a granted tree).
 */
class SafDocumentStore(private val context: Context) : DocumentStore {

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    private fun entryFrom(tree: Uri, docId: String, name: String, size: Long, mtime: Long, mime: String): LibraryEntry {
        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
        return LibraryEntry(
            documentId = docId,
            uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(),
            name = name, size = size, lastModified = mtime, isDir = isDir,
            ext = if (isDir) "" else MimeMapper.extensionOf(name),
        )
    }

    override suspend fun listChildren(treeUri: String, parentDocumentId: String?): List<LibraryEntry> =
        withContext(Dispatchers.IO) {
            val tree = Uri.parse(treeUri)
            val parentId = parentDocumentId ?: DocumentsContract.getTreeDocumentId(tree)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val out = ArrayList<LibraryEntry>()
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out += entryFrom(tree, c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4))
                }
            }
            out
        }

    override suspend fun rename(uri: String, newName: String): LibraryEntry? = withContext(Dispatchers.IO) {
        val newUri = DocumentsContract.renameDocument(context.contentResolver, Uri.parse(uri), newName)
            ?: return@withContext null
        context.contentResolver.query(newUri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                return@withContext entryFrom(newUri, c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4))
            }
        }
        null
    }

    override suspend fun delete(uri: String): Boolean = withContext(Dispatchers.IO) {
        DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uri))
    }

    override suspend fun readText(uri: String, maxBytes: Int): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(uri)).use { input ->
            requireNotNull(input)
            val buf = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val r = input.read(buf, total, maxBytes - total)
                if (r < 0) break
                total += r
            }
            val truncated = input.read() >= 0
            String(buf, 0, total, Charsets.UTF_8) to truncated
        }
    }

    override suspend fun openReadFd(uri: String): Int = openFd(uri, "r")
    override suspend fun openWriteFd(uri: String): Int = openFd(uri, "w")

    private suspend fun openFd(uri: String, mode: String): Int = withContext(Dispatchers.IO) {
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(Uri.parse(uri), mode)
            ?: error("cannot open $mode fd for $uri")
        pfd.detachFd()
    }

    override suspend fun createChild(parentUri: String, mime: String, name: String): LibraryEntry? =
        withContext(Dispatchers.IO) {
            val parent = Uri.parse(parentUri)
            val newUri = DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
                ?: return@withContext null
            LibraryEntry(
                DocumentsContract.getDocumentId(newUri), newUri.toString(), name, 0, 0, false, MimeMapper.extensionOf(name),
            )
        }
}
