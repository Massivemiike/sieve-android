package com.sieve.storage.settings

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class AndroidTreePermissionOracle(private val context: Context) : TreePermissionOracle {
    override fun isPersisted(uri: String): Boolean {
        val parsed = Uri.parse(uri)
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == parsed && it.isReadPermission && it.isWritePermission
        }
    }

    override fun canWrite(uri: String): Boolean {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: return false
        return root.exists() && root.canWrite()
    }
}
