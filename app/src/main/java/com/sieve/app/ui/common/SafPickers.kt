package com.sieve.app.ui.common

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Launches ACTION_OPEN_DOCUMENT_TREE and persists the grant before delivering the tree URI. */
@Composable
fun rememberOpenDocumentTree(onGranted: (Uri) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            onGranted(uri)
        }
    }
    return { launcher.launch(null) }
}

/** Launches ACTION_OPEN_DOCUMENT for a single file of the given MIME types. */
@Composable
fun rememberOpenDocument(mimeTypes: Array<String>, onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    return { launcher.launch(mimeTypes) }
}
