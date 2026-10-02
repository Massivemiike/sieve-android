package com.sieve.engine.repo

import com.sieve.engine.model.VideoInfo
import com.sieve.engine.update.UpdateChannel
import com.sieve.engine.update.UpdateCheck
import com.sieve.engine.update.UpdateResult
import kotlinx.coroutines.flow.Flow

sealed interface AnalyzeOutcome {
    data class Success(val info: VideoInfo) : AnalyzeOutcome
    data class Failure(val message: String) : AnalyzeOutcome
}

/**
 * Queue-facing engine contract. `download` is a COLD flow (runs on collection),
 * terminating with exactly one of Completed/Failed/Cancelled. `cancel(id)` uses
 * the same processId the queue assigned; the id is stable across pause→resume→cancel.
 * checkUpdate compares against GitHub and NEVER applies; doUpdate applies via the library.
 * [repairVersionRecord] heals a record that outlived its yt-dlp file (see there); [doUpdate] runs it first itself.
 *
 * `analyze`'s [cookiesFile] is a real, readable path to a Netscape cookies.txt. It is anonymous-first:
 * the file is only tried (once) when the anonymous attempt fails because the site wants a login, so
 * sites where cookies hurt (YouTube's degraded extractor, LinkedIn, Facebook) are never given them.
 * [proxy] and [userAgent] are the Network settings and go on every attempt, so reading a link leaves the
 * phone the same way the download will (a region-locked link then analyzes, and the real IP isn't exposed).
 */
interface YtDlpEngine {
    suspend fun analyze(
        url: String,
        cookiesBrowser: String?,
        cookiesFile: String? = null,
        proxy: String? = null,
        userAgent: String? = null,
    ): AnalyzeOutcome
    fun download(id: String, url: String, args: List<String>): Flow<EngineEvent>
    fun cancel(id: String): Boolean
    suspend fun version(): String?
    suspend fun checkUpdate(): UpdateCheck
    suspend fun doUpdate(channel: UpdateChannel = UpdateChannel.STABLE): UpdateResult

    /**
     * The library's updater says "up to date" whenever GitHub's latest tag equals its own record of the yt-dlp it
     * downloaded, without looking at the yt-dlp file. When that record is NEWER than the file that will run (Auto
     * Backup restored the prefs but not the engine files on a reinstall; a failed update put the bundled yt-dlp
     * back), this forgets the record so the next [doUpdate] downloads. True when it repaired something. Cheap and
     * safe next to running downloads; call it before deciding whether an update is due.
     */
    suspend fun repairVersionRecord(): Boolean = false
}
