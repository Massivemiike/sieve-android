package com.sieve.app.update

sealed interface UpdateStatus {
    data object UpToDate : UpdateStatus
    data class Available(val manifest: UpdateManifest) : UpdateStatus
    /** Fetch failed (no network / bad manifest) — distinct from UpToDate so the UI can say so. */
    data object Unknown : UpdateStatus
}

class AppUpdateChecker(
    private val fetcher: UpdateManifestFetcher,
    private val installedVersionCode: Int,
) {
    suspend fun check(): UpdateStatus {
        val m = fetcher.fetch() ?: return UpdateStatus.Unknown
        return if (m.versionCode > installedVersionCode) UpdateStatus.Available(m) else UpdateStatus.UpToDate
    }
}
