package com.sieve.queue.service

/**
 * Android 17 (targetSdk 37) blocks an app's connections to the local network until the user grants it
 * `ACCESS_LOCAL_NETWORK`, for every socket of the app's UID, including the yt-dlp child process (see
 * [com.sieve.engine.site.LocalNetwork]). yt-dlp only sees a timeout, which the retry rules would call a transient network
 * problem. This seam tells the [JobDriver] when that is what is happening, so the job fails once with the real cause instead.
 * :queue holds no Android permission API; :app supplies the implementation.
 */
fun interface LocalNetworkGuard {
    /**
     * True when a download of [url] with [engineArgs] would be blocked by the OS right now: the permission is required on this
     * device and not granted, and the link (or the proxy it goes through) is on the local network. Never throws.
     */
    suspend fun blocks(url: String, engineArgs: List<String>): Boolean

    companion object {
        /** No restriction: devices below Android 17, tests, previews. */
        val None = LocalNetworkGuard { _, _ -> false }
    }
}
