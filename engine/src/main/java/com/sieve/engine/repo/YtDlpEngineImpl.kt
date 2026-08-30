package com.sieve.engine.repo

import com.sieve.engine.parse.AnalyzeError
import com.sieve.engine.parse.AnalyzeParser
import com.sieve.engine.parse.ProgressParser
import com.sieve.engine.parse.StoryboardDetector
import com.sieve.engine.update.GithubReleaseApi
import com.sieve.engine.update.UpdateChannel
import com.sieve.engine.update.UpdateCheck
import com.sieve.engine.update.UpdateResult
import com.sieve.engine.update.VersionCompare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class YtDlpEngineImpl(
    private val client: YoutubeDLClient,
    private val github: GithubReleaseApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    analyzeConcurrency: Int = 2,
) : YtDlpEngine {

    private val gate = Semaphore(analyzeConcurrency)

    override suspend fun analyze(url: String, cookiesBrowser: String?): AnalyzeOutcome = withContext(io) {
        gate.withPermit {
            val hadCookies = !cookiesBrowser.isNullOrBlank()
            val firstOpts = buildList {
                add("-J"); add("--no-warnings")
                if (hadCookies) { add("--cookies-from-browser"); add(cookiesBrowser!!) }
            }
            val r1 = runCatching { client.execute("analyze", url, firstOpts) { _, _, _ -> } }
            val info1 = r1.getOrNull()?.let { runCatching { AnalyzeParser.parse(it.out) }.getOrNull() }
            if (info1 != null && !StoryboardDetector.hasOnlyStoryboards(info1)) {
                return@withPermit AnalyzeOutcome.Success(info1)
            }
            val firstErr = r1.fold(
                onSuccess = { AnalyzeError.extract(it.err, it.exitCode) },
                onFailure = { it.message ?: "analyze failed" },
            )
            if (!hadCookies) {
                return@withPermit if (info1 != null) {
                    AnalyzeOutcome.Failure("Sign-in may be required (only storyboards available)")
                } else {
                    AnalyzeOutcome.Failure(firstErr)
                }
            }
            // Retry without cookies.
            val r2 = runCatching { client.execute("analyze", url, listOf("-J", "--no-warnings")) { _, _, _ -> } }
            val info2 = r2.getOrNull()?.let { runCatching { AnalyzeParser.parse(it.out) }.getOrNull() }
            if (info2 != null && !StoryboardDetector.hasOnlyStoryboards(info2)) {
                AnalyzeOutcome.Success(info2.copy(cookieFallback = true))
            } else {
                AnalyzeOutcome.Failure(firstErr)
            }
        }
    }

    override fun download(id: String, url: String, args: List<String>): Flow<EngineEvent> = channelFlow {
        withContext(io) {
            try {
                val result = client.execute(id, url, args) { _, _, line ->
                    for (ln in line.split("\n")) {
                        if (ln.isBlank()) continue
                        val progress = ProgressParser.parseProgress(ln)
                        if (progress != null) {
                            trySend(EngineEvent.Progress(progress))
                        } else {
                            trySend(EngineEvent.Log(ProgressParser.cleanLogLine(ln), ProgressParser.parseFilePath(ln), false))
                        }
                    }
                }
                send(EngineEvent.Completed(result.exitCode))
            } catch (e: CancellationException) {
                send(EngineEvent.Cancelled)
                throw e
            } catch (e: Exception) {
                // The library collapses a non-zero exit into an exception; surface the
                // message as an ERROR log and a Completed(1) so the queue's ErrorMapper runs.
                send(EngineEvent.Log(e.message ?: "download failed", null, true))
                send(EngineEvent.Completed(1))
            }
        }
    }

    override fun cancel(id: String): Boolean = runCatching { client.destroy(id) }.getOrDefault(false)

    override suspend fun version(): String? = withContext(io) { runCatching { client.version() }.getOrNull() }

    override suspend fun checkUpdate(): UpdateCheck = withContext(io) {
        val current = runCatching { client.version() }.getOrNull()
        val latest = runCatching { github.latestTag() }.getOrNull()
        UpdateCheck(VersionCompare.isNewer(latest, current), latest, current)
    }

    override suspend fun doUpdate(channel: UpdateChannel): UpdateResult = withContext(io) {
        runCatching { client.update(channel == UpdateChannel.NIGHTLY) }
            .fold(
                onSuccess = { UpdateResult(true, it) },
                onFailure = { UpdateResult(false, it.message ?: "update failed") },
            )
    }
}
