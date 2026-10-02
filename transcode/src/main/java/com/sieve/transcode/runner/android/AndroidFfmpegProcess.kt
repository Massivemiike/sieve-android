package com.sieve.transcode.runner.android

import com.sieve.transcode.runner.FfmpegProcess
import com.sieve.transcode.runner.FfmpegProcessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Real [FfmpegProcess] backed by a [java.lang.Process]. stdout and stderr are drained on separate
 * `Dispatchers.IO` coroutines so a full pipe on one never blocks the other.
 *
 * stdout lines are re-terminated with `\n` because [com.sieve.transcode.runner.FfmpegProgressParser]
 * delimits `key=value` records on newlines; stderr lines are emitted raw (the runner treats each as
 * one log line).
 */
class AndroidFfmpegProcess(private val process: Process) : FfmpegProcess {

    override val stdout: Flow<String> = readerFlow(process.inputStream, appendNewline = true)
    override val stderr: Flow<String> = readerFlow(process.errorStream, appendNewline = false)

    private fun readerFlow(stream: InputStream, appendNewline: Boolean): Flow<String> = flow {
        val reader = stream.bufferedReader()
        try {
            while (true) {
                // destroy() (cancel escalating past 'q') closes the pipes while this read is blocked; that is
                // the end of the output, not an error. Only the read is guarded, never the emit.
                val line = try { reader.readLine() } catch (_: IOException) { null } ?: break
                emit(if (appendNewline) line + "\n" else line)
            }
        } finally {
            runCatching { reader.close() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun writeStdin(text: String) = withContext(Dispatchers.IO) {
        process.outputStream.write(text.toByteArray())
        process.outputStream.flush()
    }

    override fun destroy() = process.destroy()
    override fun destroyForcibly() { process.destroyForcibly() }

    // Interruptible, so cancelling the collector releases the IO thread instead of parking it in waitFor().
    override suspend fun awaitExit(): Int = runInterruptible(Dispatchers.IO) { process.waitFor() }

    // A TIMED OS wait: the bound holds however wedged ffmpeg is (cancel's SIGTERM grace relies on it).
    override suspend fun awaitExit(timeoutMs: Long): Boolean =
        runInterruptible(Dispatchers.IO) { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
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
