package com.sieve.transcode.runner.android

import com.sieve.transcode.runner.FfmpegProcess
import com.sieve.transcode.runner.FfmpegProcessFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Real [FfmpegProcess] backed by a [java.lang.Process]. stdout and stderr are drained on separate
 * `Dispatchers.IO` coroutines so a full pipe on one never blocks the other.
 *
 * stdout lines are re-terminated with `\n` because [com.sieve.transcode.runner.FfmpegProgressParser]
 * delimits `key=value` records on newlines; stderr lines are emitted raw (the runner treats each as
 * one log line). A line is read at most [MAX_LINE_CHARS] long ([BoundedLineReader]).
 *
 * **Why [destroyForcibly] does not call `Process.destroyForcibly()`.** On Android `java.lang.Process` is libcore's
 * `UNIXProcess`: its `destroy()` is `kill(pid, SIGTERM)` and it has no `destroyForcibly()` of its own, so the inherited
 * default (`destroy(); return this`) sends SIGTERM again. There is no SIGKILL anywhere in that API. A hung native
 * MediaCodec call ignores SIGTERM (ffmpeg only sets a flag, and its main thread then waits on the stuck decoder thread
 * forever), so the old "q, SIGTERM, SIGKILL" cancel was really "q, SIGTERM, SIGTERM": two signals a tap. On the S26 the
 * first Cancel tap on a hung transcode did nothing and the second ended it ~5 s later with a SIGABRT in the decoder thread
 * ("pthread_mutex_lock called on a destroyed mutex"), which is what ffmpeg's own handler does on the 4th signal
 * (`Received > 3 system signals, hard exiting`: exit(123) tears statics down under the running decoder thread). The real
 * SIGKILL is sent to the pid with `android.os.Process.sendSignal` (same uid, a child of this app).
 */
class AndroidFfmpegProcess internal constructor(
    private val process: Process,
    private val sigkill: (pid: Int) -> Unit,
    private val unreapedAfterKillMs: Long,
    private val nowMs: () -> Long,
) : FfmpegProcess {

    constructor(process: Process) : this(process, ::androidSigkill, UNREAPED_AFTER_KILL_MS, System::currentTimeMillis)

    /** `java.lang.Process` has no pid accessor on Android; its toString is `Process[pid=4303, hasExited=false]`. */
    private val pid: Int? = pidOf(process)

    /** When [destroyForcibly] was first called (0 = never): from then on [awaitExit] gives the kernel [unreapedAfterKillMs]. */
    private val killedAtMs = AtomicLong(0)

    override val stdout: Flow<String> = readerFlow(process.inputStream, appendNewline = true)
    override val stderr: Flow<String> = readerFlow(process.errorStream, appendNewline = false)

    private fun readerFlow(stream: InputStream, appendNewline: Boolean): Flow<String> = flow {
        val reader = BoundedLineReader(InputStreamReader(stream, Charsets.UTF_8), MAX_LINE_CHARS)
        try {
            while (true) {
                // destroy() (cancel escalating past 'q') closes the pipes while this read is blocked; that is
                // the end of the output, not an error. Only the read is guarded, never the emit.
                val line = try { reader.readLine() } catch (_: IOException) { null } ?: break
                emit(if (appendNewline) line + "\n" else line)
            }
        } finally {
            runCatching { stream.close() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Writes [text] to ffmpeg's stdin. The write runs on its own daemon thread and is abandoned after
     * [STDIN_WRITE_BOUND_MS]: cancel's `q` must never be able to hold up the escalation behind it (as an IOException,
     * which cancel already treats as "nothing left to ask").
     */
    override suspend fun writeStdin(text: String) {
        val done = CompletableDeferred<Unit>()
        Thread({
            try {
                process.outputStream.write(text.toByteArray())
                process.outputStream.flush()
                done.complete(Unit)
            } catch (t: Throwable) {
                done.completeExceptionally(t)
            }
        }, "ffmpeg-stdin").apply { isDaemon = true }.start()
        withTimeoutOrNull(STDIN_WRITE_BOUND_MS) { done.await() } ?: throw IOException("writing to ffmpeg's stdin timed out")
    }

    /** SIGTERM. `Process.destroy()` also closes the three pipes under their locks, so it too is bounded and abandoned if stuck. */
    override fun destroy() {
        val t = Thread({ runCatching { process.destroy() } }, "ffmpeg-destroy").apply { isDaemon = true }
        t.start()
        try {
            t.join(DESTROY_BOUND_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** SIGKILL to the child's pid. Without a pid (or when the signal fails) the best left is `Process.destroyForcibly()`, i.e. SIGTERM. */
    override fun destroyForcibly() {
        killedAtMs.compareAndSet(0, nowMs())
        val target = pid
        val signalled = target != null && !hasExited() && runCatching { sigkill(target) }.isSuccess
        if (signalled) {
            android.util.Log.i(TAG, "SIGKILL sent to ffmpeg pid $target")
        } else if (!hasExited()) {
            // Only SIGTERM is left (see the class doc): a codec-wedged ffmpeg will survive this. Worth a line in the log.
            android.util.Log.w(TAG, "no SIGKILL for ffmpeg ($process, pid $target): fell back to Process.destroyForcibly()")
            runCatching { process.destroyForcibly() }
        }
        closePipesInBackground()
    }

    /**
     * The kernel closes the child's pipe ends when SIGKILL lands, which is what normally ends the readers. Closing ours
     * as well releases them when the child lingers (an unkillable state) or when only SIGTERM could be sent. On a daemon
     * thread: closing a pipe waits for the stream's lock, and a stuck reader or writer may hold it.
     */
    private fun closePipesInBackground() {
        Thread({
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }, "ffmpeg-close-pipes").apply { isDaemon = true }.start()
    }

    private fun hasExited(): Boolean = try {
        process.exitValue()
        true
    } catch (_: IllegalThreadStateException) {
        false
    }

    /**
     * Interruptible, so cancelling the collector releases the IO thread instead of parking it in `waitFor()`. Polled in
     * short slices, so that a child that survives SIGKILL (a thread stuck in an uninterruptible driver call) is declared
     * gone [unreapedAfterKillMs] after the kill ([EXIT_UNREAPED]) rather than holding the run, the queue slot and the
     * foreground service for as long as it lives.
     */
    override suspend fun awaitExit(): Int = runInterruptible(Dispatchers.IO) {
        var code: Int? = null
        while (code == null) {
            code = if (process.waitFor(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) process.exitValue() else unreapedCode()
        }
        code
    }

    private fun unreapedCode(): Int? {
        val killedAt = killedAtMs.get()
        return if (killedAt != 0L && nowMs() - killedAt >= unreapedAfterKillMs) EXIT_UNREAPED else null
    }

    // A TIMED OS wait: the bound holds however wedged ffmpeg is (cancel's SIGTERM grace relies on it).
    override suspend fun awaitExit(timeoutMs: Long): Boolean =
        runInterruptible(Dispatchers.IO) { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) || unreapedCode() != null }

    internal companion object {
        /** Longest line handed on; the rest of a longer one is read and dropped. */
        const val MAX_LINE_CHARS = 8192
        const val STDIN_WRITE_BOUND_MS = 1_000L
        const val DESTROY_BOUND_MS = 1_000L
        const val WAIT_SLICE_MS = 250L

        /** How long a SIGKILLed child may stay un-reaped before [awaitExit] reports it gone anyway. */
        const val UNREAPED_AFTER_KILL_MS = 3_000L

        /** Reported for a child that did not die within [UNREAPED_AFTER_KILL_MS] of SIGKILL (128 + 9, what a killed child reports). */
        const val EXIT_UNREAPED = 137

        private const val TAG = "SieveTx"
        private val PID = Regex("""\bpid=(\d+)""")

        fun pidOf(process: Process): Int? = PID.find(process.toString())?.groupValues?.get(1)?.toIntOrNull()
    }
}

/** SIGKILL to a child of this app (same uid, so the kernel allows it). */
internal fun androidSigkill(pid: Int) {
    android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)
}

/**
 * Starts ffmpeg as a child process. `redirectErrorStream(false)` is mandatory — progress is parsed
 * from stdout, and merging stderr in would corrupt it and risk a pipe deadlock.
 */
class AndroidFfmpegProcessFactory : FfmpegProcessFactory {
    override fun start(binaryPath: String, args: List<String>): FfmpegProcess {
        val pb = ProcessBuilder(listOf(binaryPath) + args)
        pb.redirectErrorStream(false)
        return AndroidFfmpegProcess(pb.start())
    }
}
