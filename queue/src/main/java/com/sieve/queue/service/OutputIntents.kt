package com.sieve.queue.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens a finished job's output in another app. [QueueJob.filePath] holds the content Uri the sink
 * reported once finalize moved the file into user storage (or only a display path when the sink had
 * no Uri). Shared by the queue row's open button and the completion notification.
 */
object OutputIntents {
    /**
     * The Uri another app can read, or null. Only `content://` qualifies: a display path isn't a
     * location, and a `file://` Uri throws FileUriExposedException the moment it leaves the app.
     */
    fun openableUri(location: String?): Uri? =
        location?.trim()?.takeIf { it.startsWith("content://") }?.let { Uri.parse(it) }

    /** ACTION_VIEW with a read grant; the type is asked of the provider so a viewer is picked by MIME. */
    fun viewIntent(ctx: Context, uri: Uri): Intent {
        val type = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
        return Intent(Intent.ACTION_VIEW).apply {
            if (type != null) setDataAndType(uri, type) else data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Starts the viewer. False when there is nothing to open or no app can open it (never throws). */
    fun open(ctx: Context, location: String?): Boolean {
        val uri = openableUri(location) ?: return false
        return try {
            ctx.startActivity(viewIntent(ctx, uri))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
