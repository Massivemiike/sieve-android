package com.sieve.engine

import android.content.Context
import android.util.Log
import com.sieve.engine.impersonate.ImpersonationInstaller
import com.yausername.youtubedl_android.YoutubeDL

/**
 * One-time youtubedl-android initialization. `:app` calls this at startup — it cannot touch the
 * youtubedl-android dependency directly (an internal `implementation` dep of `:engine`), so the
 * engine module owns the init call.
 */
object EngineInit {
    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        try {
            YoutubeDL.getInstance().init(context)
            // Extracts the companion ffmpeg + ffprobe and registers them with youtubedl-android so
            // yt-dlp can run its post-processors (merge / audio-extract / convert). Without this,
            // any preset that needs ffmpeg hangs the download. Must run before the first download.
            com.yausername.ffmpeg.FFmpeg.getInstance().init(context)
        } catch (e: Exception) {
            // Already initialized (or a partial init from a prior launch) — safe to ignore.
        }
        installImpersonation(context)
        initialized = true
    }

    /** curl_cffi into the library's Python (it must be there before the first yt-dlp run). */
    private fun installImpersonation(context: Context) {
        // The bundle is arm64-v8a only (what release ships); an x86_64 emulator keeps plain urllib.
        if (!context.applicationInfo.nativeLibraryDir.orEmpty().endsWith("arm64")) {
            Log.i(TAG, "impersonation: skipped (not arm64)")
            return
        }
        try {
            val version = context.assets.open(ImpersonationInstaller.ASSET_VERSION).bufferedReader().use { it.readText() }
            val outcome = ImpersonationInstaller.install(
                ImpersonationInstaller.sitePackages(context.noBackupFilesDir), version,
            ) { context.assets.open(ImpersonationInstaller.ASSET_ZIP) }
            Log.i(TAG, "impersonation: $outcome")
        } catch (e: Exception) {
            // Downloads still work without it; only sites that check the TLS fingerprint fail.
            Log.w(TAG, "impersonation: install failed", e)
        }
    }

    private const val TAG = "SieveEngine"

    val isInitialized: Boolean get() = initialized
}
