package com.sieve.engine.repo

import com.sieve.engine.model.VideoInfo
import com.sieve.engine.parse.AnalyzeError
import com.sieve.engine.parse.AnalyzeException
import com.sieve.engine.parse.AnalyzeParser
import com.sieve.engine.parse.LogRedactor
import com.sieve.engine.parse.ProgressParser
import com.sieve.engine.parse.StoryboardDetector
import com.sieve.engine.site.SiteRules
import com.sieve.engine.update.GithubReleaseApi
import com.sieve.engine.update.UpdateChannel
import com.sieve.engine.update.UpdateCheck
import com.sieve.engine.update.UpdateResult
import com.sieve.engine.update.VersionCompare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class YtDlpEngineImpl(
    private val client: YoutubeDLClient,
    private val github: GithubReleaseApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    analyzeConcurrency: Int = 2,
    /** How long one analyze attempt may run before it is killed. Injectable so tests can use a tiny value. */
    private val analyzeTimeoutMs: Long = ANALYZE_TIMEOUT_MS,
) : YtDlpEngine {

    private val gate = Semaphore(analyzeConcurrency)
    private val cancelledIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Owns the analyze watchdogs. They must not be children of the caller's scope: a caller that goes away
     * cancels its children, and a cancelled watchdog can no longer kill a process that is still running.
     */
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * An update replaces the yt-dlp files in place, so it must never overlap a yt-dlp run: runs hold
     * the read side, the update the write side. A waiting update also holds back new runs (the lock
     * does not let readers barge past a queued writer), so the queue just waits for it. Only wraps
     * the blocking client calls, which acquire and release on one thread.
     */
    private val engineFiles = ReentrantReadWriteLock()

    /** Unique per-call analyze ids: the library rejects a second live call with the same process id. */
    private val analyzeSeq = AtomicLong()

    /**
     * The URL form analyze settled on for a link (e.g. Vimeo's player URL), keyed by what the user
     * pasted and by its normalized form, so the download starts with the form that works. Capped,
     * least-recently-used first out.
     */
    private val settledUrls: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > SETTLED_URL_CAP
        },
    )

    private fun rememberSettled(raw: String, normalized: String, used: String) {
        for (key in setOf(raw, raw.trim(), normalized)) if (key.isNotBlank()) settledUrls[key] = used
    }

    private class Analyzed(val info: VideoInfo, val used: String)

    /** The Network settings that apply to reading a link too, so it goes out the way the download will. */
    private class AnalyzeNet(val proxy: String?, val userAgent: String?)

    /**
     * One analyze attempt; throws on non-zero exit, timeout or unparseable output. The library
     * throws on any non-zero exit (message = full stderr), so both a thrown failure and an
     * `exitCode != 0` result are handled. `client.execute` blocks, so the timeout is a watchdog
     * that kills the process by id rather than a coroutine `withTimeout`. The watchdog is armed only
     * once the engine lock is held (the wait behind an update is not the site's fault), and a caller
     * that gives up kills the process too, so it can't keep running and hold an analyze permit.
     */
    private suspend fun runAnalyze(url: String, cookiesBrowser: String?, cookiesFile: String?, net: AnalyzeNet): VideoInfo = coroutineScope {
        val id = "analyze-${analyzeSeq.incrementAndGet()}"
        val opts = buildList {
            add("--encoding"); add("utf-8")
            add("-J"); add("--flat-playlist"); add("-I"); add("1:$ANALYZE_ENTRY_CAP")
            if (!net.proxy.isNullOrBlank()) { add("--proxy"); add(net.proxy) }
            if (!cookiesBrowser.isNullOrBlank()) { add("--cookies-from-browser"); add(cookiesBrowser) }
            if (!cookiesFile.isNullOrBlank()) { add("--cookies"); add(cookiesFile) }
            if (!net.userAgent.isNullOrBlank()) { add("--user-agent"); add(net.userAgent) }
        }
        val timedOut = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        // execute() ignores coroutine cancellation: when the caller is cancelled this child is cancelled with it
        // and is the only thing still able to stop the process.
        val callerGone = launch(Dispatchers.Default) {
            try { awaitCancellation() } finally { if (!finished.get()) runCatching { client.destroy(id) } }
        }
        try {
            val res = try {
                engineFiles.read {
                    // Gave up while waiting for an update to finish: never start it.
                    ensureActive()
                    val watchdog = watchdogScope.launch {
                        delay(analyzeTimeoutMs)
                        timedOut.set(true)
                        runCatching { client.destroy(id) }
                    }
                    try {
                        client.execute(id, url, opts) { _, _, _ -> }
                    } finally {
                        watchdog.cancel()
                    }
                }
            } catch (e: Exception) {
                if (timedOut.get()) throw AnalyzeException(ANALYZE_TIMEOUT_MESSAGE, e)
                throw e
            }
            if (res.exitCode != 0) {
                if (timedOut.get()) throw AnalyzeException(ANALYZE_TIMEOUT_MESSAGE)
                throw AnalyzeException(AnalyzeError.extract(res.err, res.exitCode), stderr = res.err)
            }
            val info = AnalyzeParser.parse(res.out)
            val warnings = res.err.lines().filter { it.trimStart().startsWith("WARNING:") }.take(MAX_WARNINGS)
            if (warnings.isEmpty()) info else info.copy(warnings = warnings)
        } finally {
            finished.set(true)
            callerGone.cancel()
        }
    }

    /** [runAnalyze] plus the alternate-URL retry (Vimeo player form) when the site rejects the first form. */
    private suspend fun analyzeSettling(url: String, cookiesBrowser: String?, cookiesFile: String?, net: AnalyzeNet): Analyzed {
        try {
            return Analyzed(runAnalyze(url, cookiesBrowser, cookiesFile, net), url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val stderr = (e as? AnalyzeException)?.stderr ?: e.message
            val alt = SiteRules.fallbackUrl(url, SiteRules.errorText(stderr)) ?: throw e
            // The alternate form's answer is the more useful error when it fails too.
            return Analyzed(runAnalyze(alt, cookiesBrowser, cookiesFile, net), alt)
        }
    }

    override suspend fun analyze(
        url: String,
        cookiesBrowser: String?,
        cookiesFile: String?,
        proxy: String?,
        userAgent: String?,
    ): AnalyzeOutcome = withContext(io) {
        val net = AnalyzeNet(proxy, userAgent)
        gate.withPermit {
            val first = analyzeAttempt(url, cookiesBrowser, cookiesFile = null, net)
            // Anonymous first (the desktop's rule for hosts where cookies hurt, applied to the cookies file
            // everywhere): the file only gets one go, and only when the site asked for a login.
            if (first is AnalyzeOutcome.Failure && !cookiesFile.isNullOrBlank() && SiteRules.looksLoginRequired(first.message)) {
                val withFile = analyzeAttempt(url, cookiesBrowser, cookiesFile, net)
                if (withFile is AnalyzeOutcome.Success) withFile else first // the original error is the useful one
            } else {
                first
            }
        }
    }

    /** One analyze (browser cookies with their anonymous fallback, then the player-URL fallback), see [analyze]. */
    private suspend fun analyzeAttempt(url: String, cookiesBrowser: String?, cookiesFile: String?, net: AnalyzeNet): AnalyzeOutcome {
        val normalized = SiteRules.normalizeUrl(url)
        val hadCookies = !cookiesBrowser.isNullOrBlank()
        return try {
            val first = analyzeSettling(normalized, cookiesBrowser, cookiesFile, net)
            // Cookies sometimes make YouTube serve the degraded (storyboard-only) extractor.
            if (hadCookies && StoryboardDetector.hasOnlyStoryboards(first.info)) {
                val fallback = runCatching { analyzeSettling(normalized, null, cookiesFile, net) }.getOrNull()
                if (fallback != null && !StoryboardDetector.hasOnlyStoryboards(fallback.info)) {
                    rememberSettled(url, normalized, fallback.used)
                    return AnalyzeOutcome.Success(fallback.info.copy(cookieFallback = true))
                }
                // else keep the original result
            }
            rememberSettled(url, normalized, first.used)
            AnalyzeOutcome.Success(first.info)
        } catch (e: CancellationException) {
            throw e
        } catch (err: Exception) {
            if (hadCookies) {
                // The cookie attempt failed outright — retry without, returned unconditionally.
                val fallback = runCatching { analyzeSettling(normalized, null, cookiesFile, net) }.getOrNull()
                if (fallback != null) {
                    rememberSettled(url, normalized, fallback.used)
                    AnalyzeOutcome.Success(fallback.info.copy(cookieFallback = true))
                } else {
                    AnalyzeOutcome.Failure(err.message ?: "analyze failed") // original error
                }
            } else {
                AnalyzeOutcome.Failure(err.message ?: "analyze failed")
            }
        }
    }

    override fun download(id: String, url: String, args: List<String>): Flow<EngineEvent> = channelFlow {
        // Only drops a stale id left by an earlier run of this job. A cancel that really predates this start is
        // re-sent by the queue once the process is up (QueueManager.launchJob) and wins over a finished run there.
        cancelledIds.remove(id)
        ensureOutputDir(args)
        // The args can hold a proxy password or auth headers: logcat ends up in bug reports.
        android.util.Log.i("SieveDL", "download start id=$id args=${LogRedactor.redactArgs(args)}")
        withContext(io) {
            // First attempt: the URL form analyze settled on, else the deterministic normalization.
            var target = settledUrls[url] ?: settledUrls[url.trim()] ?: SiteRules.normalizeUrl(url)
            // --no-warnings as on desktop (CLAUDE.md): it keeps WARNING lines out of the failure text that the
            // retry verdict and the humanizer read. Analyze keeps its warnings (VideoInfo.warnings).
            var runArgs = listOf("--encoding", "utf-8", "--no-warnings") + args
            // Each recovery runs at most once per download.
            val tried = mutableSetOf<String>()
            var drmStderr: String? = null
            while (true) {
                var exitCode = 0
                var stderr = ""
                var thrown: Exception? = null
                try {
                    val result = engineFiles.read {
                        // Cancelled while waiting for an update to finish: never start it.
                        if (cancelledIds.contains(id)) throw IllegalStateException("cancelled before start")
                        client.execute(id, target, runArgs) { _, _, line ->
                            for (ln in line.split("\n")) {
                                if (ln.isBlank()) continue
                                val progress = ProgressParser.parseProgress(ln)
                                if (progress != null) {
                                    trySend(EngineEvent.Progress(progress))
                                } else {
                                    trySend(EngineEvent.Log(LogRedactor.redact(ProgressParser.cleanLogLine(ln)), ProgressParser.parseFilePath(ln), false))
                                }
                            }
                        }
                    }
                    exitCode = result.exitCode
                    stderr = result.err
                    if (exitCode != 0) {
                        android.util.Log.e(
                            "SieveDL",
                            "EXIT=$exitCode\nSTDERR:\n${LogRedactor.redact(result.err.takeLast(4000))}\nSTDOUT:\n${LogRedactor.redact(result.out.takeLast(1500))}",
                        )
                    }
                } catch (e: CancellationException) {
                    send(EngineEvent.Cancelled)
                    throw e
                } catch (e: Exception) {
                    thrown = e
                    stderr = e.message.orEmpty()
                }

                if (thrown == null && exitCode == 0) {
                    send(EngineEvent.Completed(0))
                    return@withContext
                }

                val cancelled = cancelledIds.contains(id)
                // cancel(id) → destroy(id) kills the process and the library throws; route
                // that as a user Cancel, not an error (and never retry it).
                if (thrown != null && cancelled) {
                    cancelledIds.remove(id)
                    send(EngineEvent.Cancelled)
                    return@withContext
                }

                if (!cancelled) {
                    val errs = SiteRules.errorText(stderr)
                    // Same media under another URL form (Vimeo player URL).
                    val alt = SiteRules.fallbackUrl(target, errs)
                    if (alt != null && tried.add("alt")) {
                        send(EngineEvent.Log("[retry] Retrying with the video player URL", null, false))
                        target = alt
                        continue
                    }
                    // The chosen format is DRM-locked (Vimeo serves some streams that way):
                    // let yt-dlp test formats and fall back to a playable one.
                    if (DRM_PROTECTED.containsMatchIn(errs) && CHECK_FORMATS !in runArgs && tried.add("check-formats")) {
                        send(EngineEvent.Log("[retry] That format is DRM-locked — retrying with a playable one", null, false))
                        drmStderr = stderr
                        runArgs = runArgs + CHECK_FORMATS
                        continue
                    }
                    // Cover art embeds only into mp3/m4a/mp4/mkv/opus/flac. An AV1/VP9 + Opus merge is a .webm, and
                    // there yt-dlp saves the media and THEN fails the whole run on the embed. Keep the saved file
                    // and re-run once without the embed: yt-dlp sees the file already downloaded and only redoes
                    // the post-processing, so the user gets the video instead of a failure.
                    if (EMBED_THUMBNAIL in runArgs && THUMBNAIL_EMBED_FAILED.containsMatchIn(errs) && tried.add("embed-thumbnail")) {
                        send(EngineEvent.Log("[retry] This format can't carry a cover image — retrying without it", null, false))
                        runArgs = runArgs.filterNot { it == EMBED_THUMBNAIL }
                        continue
                    }
                }

                // Final failure: only the LAST attempt is reported — except when --check-formats found
                // nothing playable: its "Requested format is not available" would blame the preset, so
                // the DRM error that started the retry is the one to show.
                val drmOnly = drmStderr?.takeIf { REQUESTED_FORMAT.containsMatchIn(SiteRules.errorText(stderr)) }
                if (thrown != null) {
                    android.util.Log.e("SieveDL", "DL threw: ${thrown.javaClass.simpleName}\n${thrown.message?.takeLast(4000)?.let(LogRedactor::redact)}")
                    send(EngineEvent.Log(LogRedactor.redact(drmOnly ?: thrown.message ?: "download failed"), null, true))
                    send(EngineEvent.Completed(1))
                } else {
                    // Same contract as the thrown path: the failure's text travels as an error Log so
                    // the queue can show and classify the real cause, not just the exit code.
                    val text = LogRedactor.redact(drmOnly ?: stderr)
                    if (text.isNotBlank()) send(EngineEvent.Log(text, null, true))
                    send(EngineEvent.Completed(exitCode))
                }
                return@withContext
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
        runCatching { engineFiles.write { client.update(channel == UpdateChannel.NIGHTLY) } }
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

    private companion object {
        const val ANALYZE_TIMEOUT_MS = 150_000L
        const val ANALYZE_TIMEOUT_MESSAGE = "Timed out while reading this link — the site may be slow or blocking requests."

        /** Huge channels take minutes to list; list the newest 1,000 (downloading still gets them all). */
        const val ANALYZE_ENTRY_CAP = 1000
        const val MAX_WARNINGS = 5
        const val SETTLED_URL_CAP = 1000
        const val CHECK_FORMATS = "--check-formats"
        const val EMBED_THUMBNAIL = "--embed-thumbnail"
        /** yt-dlp: `ERROR: Postprocessing: Supported filetypes for thumbnail embedding are: ...` (or a cover conversion error). */
        val THUMBNAIL_EMBED_FAILED = Regex("Postprocessing:.*thumbnail", RegexOption.IGNORE_CASE)
        val DRM_PROTECTED = Regex("DRM protected", RegexOption.IGNORE_CASE)
        val REQUESTED_FORMAT = Regex("Requested format is not available", RegexOption.IGNORE_CASE)
    }
}
