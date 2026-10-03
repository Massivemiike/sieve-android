package com.sieve.transcode.runner

import com.sieve.transcode.args.FfmpegArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs a [TranscodeJob] via a [FfmpegProcessFactory], turning the process's stdout/stderr into a
 * cold [Flow] of [TranscodeEvent]. stdout feeds [FfmpegProgressParser] (emitting `Progress` only for
 * `outTimeUs > 0`, filtering ffmpeg's startup 0-blocks); stderr goes through [StderrLog] (ANSI-stripped, capped,
 * repeats collapsed, paced) and is emitted as `Log`, while a 64 KB tail is kept for the error summary.
 *
 * **A run always ends.** Three things end one that ffmpeg itself will not:
 *  - *Stall watchdog.* No `-progress` ADVANCE (out_time, frame or total_size beyond everything seen so far) for
 *    [Limits.stallTimeoutMs] stops the process (`q`, SIGTERM, SIGKILL, with short graces). stderr output does NOT count,
 *    and neither does a progress block that repeats the old numbers: a wedged codec keeps ffmpeg's main thread printing
 *    `frame=0 out_time=N/A` blocks and floods the log. Time is counted in watchdog ticks, so a stretch in which the
 *    whole app was frozen by the OS is not counted either. A stalled hardware run falls back (below); a stalled CPU
 *    run ends [TranscodeEvent.Done] with [EXIT_STALLED] and [STALL_SUMMARY].
 *    A run whose VIDEO ENCODER is MediaCodec gets a shorter bound for its FIRST FRAME only: no out_time, no frame count
 *    beyond zero and no end block ([ProgressWatch.mediaAdvanced]) within [Limits.firstProgressTimeoutMs]
 *    ([FIRST_PROGRESS_TIMEOUT_MS]) of the spawn is a stall too, because MediaCodec runs that work produce their first frame
 *    within a second or two and a codec service that died under ffmpeg before its first frame or packet would otherwise
 *    cost the whole [STALL_TIMEOUT_MS] before the CPU fallback. The muxer header's `total_size` and repeated start-up blocks
 *    do not count as a first frame, so a hang with ffmpeg still printing `frame=0 out_time=N/A` is caught too. From the
 *    first frame on the ordinary bound applies again, and CPU runs (and the retry, which is one) never get the short bound
 *    ([expectsPromptFirstProgress]).
 *  - *The pipes.* The readers are NOT children of the run: after the process is gone they get [Limits.readerDrainMs] to
 *    finish and are then abandoned (a blocking `read(2)` cannot be cancelled), so a pipe that never reaches EOF cannot hold
 *    the run, the queue slot or the foreground service.
 *  - *The collector.* Cancelling it kills the process (SIGKILL).
 *
 * **Hardware fallback, once.** A run that involves hardware ([TranscodeJob.usedHardwareEncoder], or a `*_mediacodec`
 * decoder in [TranscodeJob.inputArgs]) is retried ONCE with the encoder demoted to software when (a) the encoder failed
 * to initialise (exit != 0 and stderr matches [FfmpegErrorSummarizer.isHardwareEncoderInitFailure]), (b) it stalled, or
 * (c) it crashed (killed by SIGABRT/SIGSEGV/..., [CRASH_EXIT_CODES]: the Qualcomm codec service dying under it). The
 * only signal is the `Log` line (there is no chip or event for it). Hardware DECODERS go back to software
 * too, except `av1_mediacodec`: the bundled ffmpeg has no software AV1 decoder, so that stays (a plain retry, which
 * is also what a restarted codec service needs). A run the caller asked to stop ([run]'s `stopRequested`) is never
 * retried.
 *
 * The events leave through a bounded buffer that drops the OLDEST when the consumer is slower than ffmpeg: a slow
 * consumer (the queue persists every line it is given) must never back the pipes up into ffmpeg, which is what the
 * watchdog would then misread as a stall. Done is always the newest event, so it is never the one dropped.
 */
class FfmpegRunner(
    private val factory: FfmpegProcessFactory,
    private val binaryPath: String,
    private val limits: Limits = Limits(),
) {

    /** Every bound the runner works to; the defaults are the named constants below, tests shrink them. */
    data class Limits(
        val stallTimeoutMs: Long = STALL_TIMEOUT_MS,
        val firstProgressTimeoutMs: Long = FIRST_PROGRESS_TIMEOUT_MS,
        val stallCheckMs: Long = STALL_CHECK_MS,
        val stallQuitGraceMs: Long = STALL_QUIT_GRACE_MS,
        val stallTermGraceMs: Long = STALL_TERM_GRACE_MS,
        val reapWaitMs: Long = REAP_WAIT_MS,
        val readerDrainMs: Long = READER_DRAIN_MS,
    )

    /**
     * @param stopRequested true once the caller (cancel / pause / shutdown) has asked for this run to stop. Such a run is
     * not "a hardware crash": a cancelled hung ffmpeg dies with SIGABRT as often as not, and must not respawn on the CPU.
     */
    fun run(job: TranscodeJob, stopRequested: () -> Boolean = { false }): Flow<TranscodeEvent> = channelFlow {
        var current = job
        var fellBack = false
        while (true) {
            val attempt = runOnce(current)
            val reason = if (fellBack || stopRequested()) null else fallbackReason(current, attempt)
            if (reason != null) {
                fellBack = true
                val demoteEncoder = current.usedHardwareEncoder
                send(TranscodeEvent.Log(reason.message(demoteEncoder), false))
                current = current.copy(
                    presetArgs = if (demoteEncoder) demoteToSoftware(current.presetArgs) else current.presetArgs,
                    inputArgs = demoteInputToSoftware(current.inputArgs),
                    usedHardwareEncoder = false,
                )
                continue
            }
            send(
                TranscodeEvent.Done(
                    exitCode = if (attempt.stalled) EXIT_STALLED else attempt.code,
                    errorSummary = when {
                        attempt.stalled -> STALL_SUMMARY
                        attempt.code != 0 -> FfmpegErrorSummarizer.summarize(attempt.tail)
                        else -> null
                    },
                    stderrTail = attempt.lastLines.joinToString("\n"),
                ),
            )
            break
        }
    }.buffer(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)

    private class Attempt(val code: Int, val stalled: Boolean, val tail: String, val lastLines: List<String>)

    private enum class FallbackReason(private val what: String) {
        INIT_FAILED("HW encoder failed"),
        STALLED("Hardware codec stopped making progress"),
        CRASHED("Hardware codec crashed");

        fun message(demotedEncoder: Boolean) = if (demotedEncoder) "$what, retrying on software encoder" else "$what, retrying"
    }

    private fun fallbackReason(job: TranscodeJob, attempt: Attempt): FallbackReason? {
        if (!involvesHardware(job)) return null
        return when {
            attempt.stalled -> FallbackReason.STALLED
            attempt.code in CRASH_EXIT_CODES -> FallbackReason.CRASHED
            job.usedHardwareEncoder && attempt.code != 0 && FfmpegErrorSummarizer.isHardwareEncoderInitFailure(attempt.tail) ->
                FallbackReason.INIT_FAILED
            else -> null
        }
    }

    /** One process, start to finish. Never throws for the process's own failure; throws what the process seam throws. */
    private suspend fun ProducerScope<TranscodeEvent>.runOnce(job: TranscodeJob): Attempt {
        val events: SendChannel<TranscodeEvent> = this
        val process = factory.start(binaryPath, buildFullArgs(job))
        // Detached from this coroutine on purpose (see the class doc): same dispatcher, own Job.
        val readers = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob())
        val readerFailure = AtomicReference<Throwable?>(null)
        val watch = ProgressWatch()
        val parser = FfmpegProgressParser(job.totalDurationSec)
        val log = StderrLog()
        try {
            val outJob = readers.launch {
                guarded(readerFailure) {
                    process.stdout.collect { chunk ->
                        parser.onChunk(chunk).forEach { p ->
                            watch.observe(p)
                            if (p.outTimeUs > 0) events.trySend(TranscodeEvent.Progress(p))
                        }
                    }
                }
            }
            val errJob = readers.launch {
                guarded(readerFailure) { process.stderr.collect { raw -> log.onLine(raw) { events.trySend(it) } } }
            }
            val exit = readers.async { process.awaitExit() }

            val (code, stalled) = awaitExitOrStall(process, exit, watch, expectsPromptFirstProgress(job))

            // Bounded: the process is gone, so the pipes normally hit EOF at once. A read that does not return is abandoned.
            withTimeoutOrNull(limits.readerDrainMs) {
                outJob.join()
                errJob.join()
            }
            log.finish { events.trySend(it) }
            readerFailure.get()?.let { throw it }
            return Attempt(code, stalled, log.tail(), log.lastLines())
        } catch (t: Throwable) {
            // The collector went away (service teardown) or the seam failed: don't leave ffmpeg running as an orphan.
            process.destroyForcibly()
            throw t
        } finally {
            readers.cancel()
        }
    }

    private suspend fun guarded(failure: AtomicReference<Throwable?>, body: suspend () -> Unit) {
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failure.compareAndSet(null, t)
        }
    }

    /**
     * The exit code, and whether the watchdog had to stop the process to get it. Two bounds, both counted in ticks:
     *  - quiet for [Limits.stallTimeoutMs]: no [ProgressWatch.version] change (any advance, the output size included);
     *  - a [promptFirstProgress] run with no MEDIA progress ([ProgressWatch.mediaAdvanced]: an out_time, a frame or the end
     *    block) [Limits.firstProgressTimeoutMs] after the SPAWN. Measured from the spawn and not from the last advance, and
     *    not cleared by the output size: ffmpeg's muxer header alone makes `total_size` non-zero, and a codec that is open
     *    but wedged still prints `frame=0 total_size=<header> out_time=N/A` blocks every half second.
     */
    private suspend fun awaitExitOrStall(
        process: FfmpegProcess,
        exit: Deferred<Int>,
        watch: ProgressWatch,
        promptFirstProgress: Boolean,
    ): Pair<Int, Boolean> {
        var quietMs = 0L
        var sinceSpawnMs = 0L
        var seen = watch.version
        while (true) {
            // One tick per wait: counted, not measured, so a frozen app does not add up to a stall.
            val code = withTimeoutOrNull(limits.stallCheckMs) { exit.await() }
            if (code != null) return code to false
            sinceSpawnMs += limits.stallCheckMs
            val now = watch.version
            if (now != seen) {
                seen = now
                quietMs = 0L
            } else {
                quietMs += limits.stallCheckMs
            }
            val noFirstFrame = promptFirstProgress && !watch.mediaAdvanced && sinceSpawnMs >= limits.firstProgressTimeoutMs
            if (noFirstFrame || quietMs >= limits.stallTimeoutMs) {
                cancel(process, limits.stallQuitGraceMs, limits.stallTermGraceMs)
                return (withTimeoutOrNull(limits.reapWaitMs) { exit.await() } ?: EXIT_STALLED) to true
            }
        }
    }

    /**
     * Stops a process, escalating: ask ffmpeg to quit (`q`); SIGTERM if it hasn't exited within [graceMs];
     * SIGKILL if it still hasn't within [termGraceMs] (a wedged native MediaCodec call ignores both, and ffmpeg's own handler
     * only gives up on the 4th SIGTERM). Every wait is the process's own TIMED wait, so the bounds hold even when
     * ffmpeg is stuck. Returns whether the process was seen to exit.
     *
     * Safe on an already-exited process (output being saved, HW->SW retry gap): the closed stdin pipe
     * makes the `q` write throw, which only means there is nothing left to ask.
     */
    suspend fun cancel(process: FfmpegProcess, graceMs: Long = 2000, termGraceMs: Long = TERM_GRACE_MS): Boolean {
        try {
            process.writeStdin("q")
        } catch (_: IOException) {
            // EPIPE / "Stream closed": the process is gone (or going) — fall through to the exit check.
        }
        if (process.awaitExit(graceMs)) return true
        process.destroy()
        if (process.awaitExit(termGraceMs)) return true
        process.destroyForcibly()
        return process.awaitExit(KILL_REAP_MS)
    }

    companion object {
        const val STDERR_MAX = 65536

        /** How long SIGTERM gets before [cancel] escalates to SIGKILL. */
        const val TERM_GRACE_MS = 1000L

        /** How long [cancel] waits to see the process gone after SIGKILL. */
        const val KILL_REAP_MS = 2000L

        /**
         * No `-progress` advance for this long ends a run. ffmpeg prints a progress block every 0.5 s whatever the encode
         * speed (`-stats_period`), and out_time / frame advance with every packet the muxer writes, so even AV1 4K at 0.1x
         * advances every few seconds. The slow places are the ones before the first packet (probing, filter and encoder
         * start-up, the encoder's look-ahead: tens of seconds for a 4K software encode on a phone) and after the last
         * (the `+faststart` rewrite of a multi-GB output): two minutes covers them with room to spare. It is the bound for a
         * hang after progress began and for every software run (a hang costs two minutes before the CPU fallback, with Cancel
         * ending it at any time); a MediaCodec encode that has not produced its first frame is held to the much shorter
         * [FIRST_PROGRESS_TIMEOUT_MS] instead. Hardware runs of the same clip take seconds.
         */
        const val STALL_TIMEOUT_MS = 120_000L

        /**
         * A run whose video encoder is MediaCodec ([expectsPromptFirstProgress]) that has not produced its first frame this long
         * after its spawn ([ProgressWatch.mediaAdvanced]: no out_time, no frame count, no end block; the muxer header's
         * `total_size` does not count) is stalled, so the CPU fallback starts after about 20 s instead of [STALL_TIMEOUT_MS].
         * Measured on the S26 (signed 1.0.4, five H.264 720p MediaCodec runs of a 19 s clip): four took 0.7-0.8 s from start to
         * saved file, so their first frames came in well under a second; the fifth hung in the Qualcomm codec service and the
         * 120 s watchdog killed it 121 s after its start. That the hung run showed no media progress at all is inferred: the
         * logcat has only the SIGKILL line, and the 121 s is a polled notification timestamp. Whether it printed nothing or
         * kept printing `frame=0 total_size=<header> out_time=N/A` blocks is not known, which is why the bound waits for a
         * frame or an out_time and not for any output. 20 s is more than 20x the healthy figure (slow storage, a cold codec
         * service and a busy phone included) and about a sixth of what the hang cost. It is NOT a bound for software
         * encodes: those can take tens of seconds before their first packet (see [STALL_TIMEOUT_MS]).
         */
        const val FIRST_PROGRESS_TIMEOUT_MS = 20_000L

        /** The watchdog's tick. */
        const val STALL_CHECK_MS = 1_000L

        /** A stalled process gets `q` this long, then SIGTERM this long, then SIGKILL (it is hung: it will not answer). */
        const val STALL_QUIT_GRACE_MS = 500L
        const val STALL_TERM_GRACE_MS = 500L

        /** After the watchdog's kill, how long to wait for the exit code before reporting the stall without it. */
        const val REAP_WAIT_MS = 3_000L

        /** After the process is gone, how long the stdout/stderr readers get to reach EOF before they are abandoned. */
        const val READER_DRAIN_MS = 2_000L

        /** Events buffered for a slower consumer; past it the oldest are dropped. */
        const val EVENT_BUFFER = 256

        /** [StderrLog]: lines passed at once, then per second. */
        const val LOG_BURST_LINES = 300
        const val LOG_LINES_PER_SECOND = 50

        /** [TranscodeEvent.Done.exitCode] of a run the watchdog stopped (`timeout(1)`'s code for the same thing). */
        const val EXIT_STALLED = 124
        const val STALL_SUMMARY = "ffmpeg stopped making progress"

        /**
         * Exit codes (128 + signal) of an ffmpeg that crashed: SIGILL, SIGTRAP, SIGABRT, SIGBUS, SIGFPE, SIGSEGV. Not
         * SIGKILL / SIGTERM: those are a stop, not a crash.
         */
        val CRASH_EXIT_CODES = setOf(132, 133, 134, 135, 136, 139)

        val ERROR_LINE = Regex("error|failed|invalid|cannot|unable|denied", RegexOption.IGNORE_CASE)
        internal val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")

        fun buildFullArgs(job: TranscodeJob): List<String> =
            listOf("-y", "-progress", "pipe:1") + job.inputArgs + listOf("-i", job.inputPath) + job.presetArgs + listOf(job.outputPath)

        /** Hardware in the run: the encoder, or a MediaCodec decoder forced onto the input. */
        internal fun involvesHardware(job: TranscodeJob): Boolean =
            job.usedHardwareEncoder || hardwareDecoderIndices(job.inputArgs).isNotEmpty()

        /**
         * Whether [FIRST_PROGRESS_TIMEOUT_MS] applies to [job]: the video encoder the args actually select is MediaCodec
         * ([videoEncoderOf] ends with `_mediacodec`), so the first frames come out within seconds. [TranscodeJob.usedHardwareEncoder]
         * alone is not enough: it is only the user's encoder toggle, and on a phone with a hardware encoder every preset runs with
         * it set, including the ones whose encoder is always software (AV1, VP9, ProRes, DNxHR, DVD, GIF, WebP, the audio-only
         * presets). A 4K `libsvtav1` run needs tens of seconds before its first packet, so it must keep [STALL_TIMEOUT_MS].
         * Not when only the decoder is hardware (the CPU encoder behind it can need just as long), and not for the retry, whose
         * encoder was demoted to software. Not when the job seeks with `-ss` either: `FfmpegArgs` places it after `-i`, where
         * ffmpeg decodes and drops everything before it, so nothing advances for as long as that takes (minutes, for a deep seek
         * into a long file).
         */
        internal fun expectsPromptFirstProgress(job: TranscodeJob): Boolean =
            job.usedHardwareEncoder &&
                videoEncoderOf(job.presetArgs)?.endsWith("_mediacodec") == true &&
                "-ss" !in job.presetArgs && "-ss" !in job.inputArgs

        /**
         * The video encoder [presetArgs] select: the value of the LAST `-c:v` / `-vcodec` / `-codec:v` (ffmpeg lets a later
         * option override an earlier one), or null when there is none (audio-only and GIF presets name no encoder).
         */
        internal fun videoEncoderOf(presetArgs: List<String>): String? {
            val i = presetArgs.indexOfLast { it == "-c:v" || it == "-vcodec" || it == "-codec:v" }
            return if (i >= 0) presetArgs.getOrNull(i + 1) else null
        }

        /** Positions of `-c:v <x>_mediacodec` pairs in [inputArgs]. */
        private fun hardwareDecoderIndices(inputArgs: List<String>): List<Int> =
            inputArgs.indices.filter { i -> inputArgs[i] == "-c:v" && inputArgs.getOrNull(i + 1)?.endsWith("_mediacodec") == true }

        /**
         * The input args of the software retry: a hardware decoder is dropped so ffmpeg picks its software one, except
         * `av1_mediacodec`, because the bundled ffmpeg has no software AV1 decoder (see `SourceProbe.requiredInputArgs`).
         */
        internal fun demoteInputToSoftware(inputArgs: List<String>): List<String> {
            val drop = hardwareDecoderIndices(inputArgs).filter { inputArgs[it + 1] != "av1_mediacodec" }.flatMap { listOf(it, it + 1) }.toSet()
            return inputArgs.filterIndexed { i, _ -> i !in drop }
        }

        /**
         * Swap the video codec token after `-c:v` from MediaCodec to its software counterpart (and, for
         * libx264, pin 8-bit 4:2:0 — the HW args carry no `-pix_fmt`, see [FfmpegArgs.withSoftwarePixFmt]).
         *
         * Codec swap only, per plan. Re-applying an explicit `-threads` cap after demotion
         * (invariant #16) needs the requested-thread count, which [TranscodeJob] does not carry; the
         * queue layer (a later plan) owns that. Omitting it is safe — ffmpeg auto-threads libx264/
         * libx265 within x265's 16-thread limit when `-threads` is absent.
         */
        fun demoteToSoftware(args: List<String>): List<String> {
            val cIdx = args.indexOf("-c:v")
            if (cIdx < 0 || cIdx + 1 >= args.size) return args
            val out = args.toMutableList()
            out[cIdx + 1] = when (out[cIdx + 1]) {
                "h264_mediacodec" -> "libx264"
                "hevc_mediacodec" -> "libx265"
                else -> out[cIdx + 1]
            }
            return FfmpegArgs.withSoftwarePixFmt(out)
        }
    }
}

/**
 * Whether ffmpeg's `-progress` is MOVING, as opposed to merely printing. A block counts when out_time, the frame count or
 * the output size is beyond everything seen so far; the start-up blocks (`frame=0`, `out_time=N/A`) and a block that
 * repeats its predecessor do not, which is how a codec wedged under a still-printing ffmpeg is told from a slow encode.
 *
 * Two readings of the same blocks: [version] changes on ANY advance (what the two-minute watchdog is quiet about), and
 * [mediaAdvanced] only when the MEDIA moved (what the first-progress bound waits for). They differ by the output size: the
 * muxer header can make `total_size` non-zero (48 bytes has been seen) before a single frame has been encoded, so a block
 * `frame=0 total_size=48 out_time=N/A` moves [version] once and is not [mediaAdvanced].
 * Written by the stdout reader, read by the watchdog: only [version] and [mediaAdvanced] are shared.
 */
internal class ProgressWatch {
    private var bestOutUs = 0L
    private var bestFrame = 0L
    private var bestSize = 0L
    private val moves = AtomicLong(0)

    @Volatile private var media = false

    /** Changes whenever the progress advanced. */
    val version: Long get() = moves.get()

    /** Whether the media itself has advanced since the run began: an out_time or a frame count beyond zero, or the end block. */
    val mediaAdvanced: Boolean get() = media

    fun observe(p: FfmpegProgress) {
        var moved = p.isEnd
        var mediaMoved = p.isEnd
        if (p.outTimeUs > bestOutUs) { bestOutUs = p.outTimeUs; moved = true; mediaMoved = true }
        val frame = p.frame
        if (frame != null && frame > bestFrame) { bestFrame = frame; moved = true; mediaMoved = true }
        val size = p.totalSize
        if (size != null && size > bestSize) { bestSize = size; moved = true }
        if (mediaMoved) media = true
        if (moved) moves.incrementAndGet()
    }
}
