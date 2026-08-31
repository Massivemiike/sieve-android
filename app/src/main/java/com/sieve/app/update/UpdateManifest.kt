package com.sieve.app.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The update descriptor hosted on the website (mirrored to GitHub Releases). */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val minSdk: Int = 26,
    val changelog: String = "",
)

object UpdateManifestJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Lenient parse; returns null on malformed input or a missing required field. */
    fun parse(text: String): UpdateManifest? =
        runCatching { json.decodeFromString<UpdateManifest>(text) }.getOrNull()
}
