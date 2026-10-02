package com.sieve.app.di

import android.util.Log
import com.sieve.engine.repo.YtDlpEngine
import com.sieve.engine.update.UpdateChannel

/**
 * Keeps yt-dlp current WITHOUT user action (the launch-time job [AppGraph] starts off the main thread). The bundled binary
 * (youtubedl-android 0.18.1 ships yt-dlp 2025.11.12) is already too old for YouTube (SABR streaming → downloads fail out of the
 * box), so a fresh install MUST self-update before it can download. Throttled to once per 12h; failures are silent (offline first
 * launch just tries again next open, and Settings keeps the manual Update button).
 *
 * The library's updater deletes and recreates the yt-dlp directory IN PLACE, so it must never run underneath a live download:
 * this first waits (up to 2 h, see [awaitIdle]) until no download is PREPARING/RUNNING and skips the update if that never
 * happens. The 12 h throttle is stamped only when the update actually succeeded, so a failed or skipped attempt is retried on
 * the next open.
 *
 * First of all it asks the engine to repair a stale version record ([YtDlpEngine.repairVersionRecord]): Auto Backup can restore
 * youtubedl-android's prefs (which name a newer yt-dlp) onto an install whose engine files are the APK's bundled, older ones, and
 * the library's updater then says "up to date" forever. After a repair the update is due NOW whatever the throttle says, and the
 * throttle stamp is forgotten so that an attempt that fails or is skipped is retried on the next open, not 12 h later.
 */
internal class YtDlpAutoUpdate(
    private val engine: YtDlpEngine,
    /** When the last successful auto-update finished (epoch ms), 0 when none is known. */
    private val lastUpdatedAt: suspend () -> Long,
    private val stampUpdated: suspend (atMs: Long) -> Unit,
    private val forgetStamp: suspend () -> Unit,
    /** True once no download is running (waits for it); false when that did not happen in time. */
    private val awaitIdle: suspend () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run() {
        runCatching {
            // A failing check must never cost the update itself.
            val repaired = runCatching { engine.repairVersionRecord() }.getOrDefault(false)
            if (repaired) forgetStamp()
            val last = if (repaired) 0L else lastUpdatedAt()
            if (now() - last < THROTTLE_MS) return
            if (!awaitIdle()) {
                Log.i(TAG, "yt-dlp auto-update skipped: downloads still running")
                return
            }
            val result = engine.doUpdate(UpdateChannel.STABLE)
            if (result.ok) {
                stampUpdated(now())
                Log.i(TAG, "yt-dlp auto-update done (now ${engine.version()})")
            } else {
                Log.w(TAG, "yt-dlp auto-update failed: ${result.output}")
            }
        }.onFailure { Log.w(TAG, "yt-dlp auto-update skipped: ${it.message}") }
    }

    companion object {
        const val THROTTLE_MS = 12 * 60 * 60 * 1000L
        const val IDLE_TIMEOUT_MS = 2 * 60 * 60 * 1000L
        const val IDLE_SETTLE_MS = 5_000L
        private const val TAG = "SieveEngine"
    }
}
