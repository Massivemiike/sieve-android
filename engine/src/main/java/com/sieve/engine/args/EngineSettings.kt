package com.sieve.engine.args

/** Global settings fed to [YtdlpArgs.build]. Mirrors the desktop `settings`. */
data class EngineSettings(
    val outputPath: String = "",
    val concurrentFragments: Int = 1,
    val proxy: String = "",
    val cookiesBrowser: String = "",
    val cookiesFile: String = "",
    val userAgent: String = "",
    val geoBypassCountry: String = "",
    /** MUST preserve insertion order. */
    val customHeaders: Map<String, String> = emptyMap(),
    val sleepInterval: Int = 0,
    val maxFilesize: String = "",
    val autoArchive: Boolean = false,
    val archiveFile: String = "",
)
