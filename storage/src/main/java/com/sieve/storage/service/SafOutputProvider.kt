package com.sieve.storage.service

import com.sieve.queue.core.PreparedOutput
import com.sieve.queue.core.QueueJob
import com.sieve.queue.service.OutputLocationProvider
import com.sieve.storage.naming.StoragePaths
import com.sieve.storage.sink.DestinationSink

/** Chooses the destination sink for a job (SAF tree / MediaStore / app-files). */
interface SinkSelector {
    suspend fun select(job: QueueJob): DestinationSink
}

/**
 * SAF/MediaStore-backed [OutputLocationProvider]. `prepare` hands the native job a REAL work dir
 * under app files; `finalize` (Task 9) stream-copies the produced file(s) into the destination sink;
 * `discard` cleans the work dir. Never hands a content:// URI to yt-dlp/ffmpeg.
 */
class SafOutputProvider(
    private val filesDirPath: String,
    private val fs: WorkDirFs,
    private val selector: SinkSelector,
    private val defaultTemplate: String = "%(title)s [%(id)s].%(ext)s",
) : OutputLocationProvider {

    override suspend fun prepare(job: QueueJob): PreparedOutput {
        val workDir = StoragePaths.workDir(filesDirPath, job.id)
        // Idempotent: only mkdirs; never wipe (yt-dlp -c resume relies on this).
        fs.mkdirs(workDir)
        val template = job.output.outputTemplate.takeIf { it.isNotBlank() } ?: defaultTemplate
        return PreparedOutput(workDir = workDir, workFileTemplate = template)
    }

    override suspend fun finalize(job: QueueJob, prepared: PreparedOutput) =
        throw NotImplementedError("finalize lands in Task 9")

    override suspend fun discard(job: QueueJob, prepared: PreparedOutput) {
        if (fs.exists(prepared.workDir)) fs.deleteRecursively(prepared.workDir)
    }
}
