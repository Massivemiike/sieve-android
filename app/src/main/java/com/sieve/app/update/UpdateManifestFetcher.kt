package com.sieve.app.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Seam over an HTTP GET, so the fetcher is unit-testable. Mirrors the engine's GithubReleaseApi. */
interface HttpGet {
    suspend fun get(url: String): String?
}

class RealHttpGet(private val io: CoroutineDispatcher = Dispatchers.IO) : HttpGet {
    override suspend fun get(url: String): String? = withContext(io) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Sieve")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }
}

/** Tries the website manifest first, then the GitHub Releases mirror. */
class UpdateManifestFetcher(
    private val primaryUrl: String,
    private val mirrorUrl: String,
    private val http: HttpGet,
) {
    suspend fun fetch(): UpdateManifest? {
        http.get(primaryUrl)?.let { UpdateManifestJson.parse(it) }?.let { return it }
        return http.get(mirrorUrl)?.let { UpdateManifestJson.parse(it) }
    }
}
