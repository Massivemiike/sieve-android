package com.sieve.storage.settings

/** Injected grant facts, so the stale-grant decision is pure/JVM-testable (Android oracle in Task 11). */
interface TreePermissionOracle {
    fun isPersisted(uri: String): Boolean   // uri in persistedUriPermissions with r+w
    fun canWrite(uri: String): Boolean       // DocumentFile.fromTreeUri(...)?.canWrite()
}

object TreeValidator {
    fun validate(uri: String?, oracle: TreePermissionOracle): Boolean {
        if (uri.isNullOrBlank()) return false
        return oracle.isPersisted(uri) && oracle.canWrite(uri)
    }
}
