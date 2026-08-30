package com.sieve.engine.repo

import com.sieve.engine.model.VideoInfo
import com.sieve.engine.parse.AnalyzeError
import com.sieve.engine.parse.AnalyzeException
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class YtDlpEngineImpl(
    private val client: YoutubeDLClient,
    private val github: GithubReleaseApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    analyzeConcurrency: Int = 2,
) : YtDlpEngine {

    private val gate = Semaphore(analyzeConcurrency)
    private val cancelledIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** One analyze attempt; throws on non-zero exit or unparseable output (mirrors runAnalyze). */
    private fun runAnalyze(url: String, cookiesBrowser: String?): VideoInfo {
        val opts = buildList {
            add("-J"); add("--no-warnings")
            if (!cookiesBrowser.isNullOrBlank()) { add("--cookies-from-browser"); add(cookiesBrowser) }
        }
        val res = client.execute("analyze", url, opts) { _, _, _ -> }
        if (res.exitCode != 0) throw AnalyzeException(AnalyzeError.extract(res.err, res.exitCode))
        return AnalyzeParser.parse(res.out)
    }

    override suspend fun analyze(url: String, cookiesBrowser: String?): AnalyzeOutcome = withContext(io) {
        gate.withPermit {
            val hadCookies = !cookiesBrowser.isNullOrBlank()
            try {
                val result = runAnalyze(url, cookiesBrowser)
                // Cookies sometimes make YouTube serve the degraded (storyboard-only) extractor.
                if (hadCookies && StoryboardDetector.hasOnlyStoryboards(result)) {
                    val fallback = runCatching { runAnalyze(url, null) }.getOrNull()
                    if (fallback != null && !StoryboardDetector.hasOnlyStoryboards(fallback)) {
                        return@withPermit AnalyzeOutcome.Success(fallback.copy(cookieFallback = true))
                    }
                    // else keep the original result
                }
                AnalyzeOutcome.Success(result)
            } catch (err: Exception) {
                if (hadCookies) {
                    // The cookie attempt failed outright — retry without, returned unconditionally.
                    val fallback = runCatching { runAnalyze(url, null) }.getOrNull()
                    if (fallback != null) {
                        AnalyzeOutcome.Success(fallback.copy(cookieFallback = true))
                    } else {
                        AnalyzeOutcome.Failure(err.message ?: "analyze failed") // original error
                    }
                } else {
                    AnalyzeOutcome.Failure(err.message ?: "analyze failed")
                }
            }
        }
    }

    override fun download(id: String, url: String, args: List<String>): Flow<EngineEvent> = channelFlow {
        cancelledIds.remove(id)
        ensureOutputDir(args)
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
                // cancel(id) → destroy(id) kills the process and the library throws; route
                // that as a user Cancel, not an error.
                if (cancelledIds.remove(id)) {
                    send(EngineEvent.Cancelled)
                } else {
                    send(EngineEvent.Log(e.message ?: "download failed", null, true))
                    send(EngineEvent.Completed(1))
                }
            }
        }
    }.buffer(Channel.UNLIMITED) // never drop a progress frame (incl. the terminal 100%)

    override fun cancel(id: String): Boolean {
        cancelledIds.add(id)
        return runCatching { client.destroy(id) }.getOrDefault(false)
    }

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

    /** Desktop/CLAUDE.md invariant: the output dir must exist before yt-dlp runs. */
    private fun ensureOutputDir(args: List<String>) {
        val i = args.indexOf("-P")
        if (i >= 0 && i + 1 < args.size) {
            val path = args[i + 1]
            if (!path.startsWith("content://")) runCatching { File(path).mkdirs() }
        }
    }
}
