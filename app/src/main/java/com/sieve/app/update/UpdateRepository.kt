package com.sieve.app.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.Unknown,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
)

/**
 * Orchestrates check → download → **verify (sha256 + signer)** → install. The verify gate is
 * mandatory: a checksum or signer mismatch aborts and NEVER installs. Function seams keep it
 * unit-testable without Android.
 */
class UpdateRepository(
    private val checker: AppUpdateChecker,
    private val download: suspend (url: String, versionCode: Int, onProgress: (Float) -> Unit) -> File?,
    private val verifySha: (File, String) -> Boolean,
    private val verifySigner: (File) -> Boolean,
    private val install: suspend (File) -> Boolean,
) {
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    suspend fun checkNow() {
        _state.value = _state.value.copy(checking = true, error = null)
        val s = checker.check()
        _state.value = _state.value.copy(checking = false, status = s)
    }

    suspend fun downloadAndInstall(manifest: UpdateManifest) {
        _state.value = _state.value.copy(downloading = true, progress = 0f, error = null)
        val apk = download(manifest.apkUrl, manifest.versionCode) { p ->
            _state.value = _state.value.copy(progress = p)
        }
        if (apk == null) {
            _state.value = _state.value.copy(downloading = false, error = "Download failed")
            return
        }
        if (!verifySha(apk, manifest.sha256)) {
            apk.delete()
            _state.value = _state.value.copy(downloading = false, error = "Checksum mismatch — update aborted")
            return
        }
        if (!verifySigner(apk)) {
            apk.delete()
            _state.value = _state.value.copy(downloading = false, error = "Signature mismatch — update aborted")
            return
        }
        val started = install(apk)
        _state.value = _state.value.copy(downloading = false, error = if (started) null else "Could not start the installer")
    }

    companion object {
        // Owner-editable: the website manifest is primary; the GitHub Releases asset is the mirror.
        const val PRIMARY_MANIFEST_URL = "https://REPLACE-ME.example/sieve/update-manifest.json"
        const val MIRROR_MANIFEST_URL =
            "https://github.com/Massivemiike/sieve-android/releases/latest/download/update-manifest.json"

        fun from(context: Context, installedVersionCode: Int): UpdateRepository {
            val fetcher = UpdateManifestFetcher(PRIMARY_MANIFEST_URL, MIRROR_MANIFEST_URL, RealHttpGet())
            val downloader = ApkDownloader(context)
            val installer = ApkInstaller(context)
            return UpdateRepository(
                checker = AppUpdateChecker(fetcher, installedVersionCode),
                download = { url, vc, onProgress -> downloader.download(url, vc, onProgress) },
                verifySha = { f, sha -> ApkVerifier.matchesSha256(f, sha) },
                verifySigner = { f -> ApkVerifier.apkSignerMatchesInstalled(context, f) },
                install = { f -> installer.install(f) },
            )
        }
    }
}
