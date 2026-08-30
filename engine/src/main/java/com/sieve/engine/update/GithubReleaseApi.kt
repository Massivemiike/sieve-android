package com.sieve.engine.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Fetches the latest yt-dlp release tag from GitHub (for the check-only update path). */
interface GithubReleaseApi {
    suspend fun latestTag(): String?
}

class GithubReleaseApiImpl(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : GithubReleaseApi {
    private val tagRe = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

    override suspend fun latestTag(): String? = withContext(io) {
        try {
            val conn = (URL(RELEASES_LATEST).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Sieve")
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            if (conn.responseCode !in 200..299) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            tagRe.find(body)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val RELEASES_LATEST = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
    }
}
