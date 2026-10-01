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
 *
 * `analyze`'s [cookiesFile] is a real, readable path to a Netscape cookies.txt. It is anonymous-first:
 * the file is only tried (once) when the anonymous attempt fails because the site wants a login, so
 * sites where cookies hurt (YouTube's degraded extractor, LinkedIn, Facebook) are never given them.
 */
interface YtDlpEngine {
    suspend fun analyze(url: String, cookiesBrowser: String?, cookiesFile: String? = null): AnalyzeOutcome
    fun download(id: String, url: String, args: List<String>): Flow<EngineEvent>
    fun cancel(id: String): Boolean
    suspend fun version(): String?
    suspend fun checkUpdate(): UpdateCheck
    suspend fun doUpdate(channel: UpdateChannel = UpdateChannel.STABLE): UpdateResult
}
