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
) {
    companion object {
        /**
         * The desktop New Download form's starting toggles (NewDownload.tsx `opts` state): embed the title /
         * uploader / chapters and the thumbnail as cover art. The desktop passes them as `toggleOpts` for
         * EVERY preset (audio and archive too); a preset that already carries one is de-duplicated by
         * [YtdlpArgs.build]. Insertion order is the emission order. No `--convert-thumbnails`: the desktop
         * doesn't pair one, yt-dlp converts a webp cover itself when it embeds.
         */
        val DEFAULT_TOGGLES: Map<String, ToggleValue> = linkedMapOf(
            "--embed-metadata" to ToggleValue.On,
            "--embed-thumbnail" to ToggleValue.On,
        )
    }
}
