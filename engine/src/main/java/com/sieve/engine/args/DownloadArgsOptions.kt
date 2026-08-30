package com.sieve.engine.args

/** Per-download options fed to [YtdlpArgs.build]. Mirrors the desktop builder's `opts`. */
data class DownloadArgsOptions(
    val format: String,
    val outputPath: String? = null,
    val outputTemplate: String? = null,
    val extraArgs: List<String>? = null,
    /** MUST preserve insertion order (LinkedHashMap / linkedMapOf). */
    val toggleOpts: Map<String, ToggleValue>? = null,
    val subtitleLangs: List<String>? = null,
    val audioOnly: Boolean = false,
    val thumbnailOnly: Boolean = false,
    val infoOnly: Boolean = false,
    val playlistItems: String? = null,
    val playlistEnd: Int? = null,
    val downloadArchive: String? = null,
    val speedLimit: String? = null,
)
