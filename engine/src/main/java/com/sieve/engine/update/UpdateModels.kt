package com.sieve.engine.update

/** Result of applying an engine update (`yt-dlp -U` analog). */
data class UpdateResult(val ok: Boolean, val output: String)

/** Result of a non-applying update check (GitHub-releases comparison). */
data class UpdateCheck(val updateAvailable: Boolean, val latest: String?, val current: String?)
