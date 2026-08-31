package com.sieve.storage.service

import com.sieve.queue.core.FinalLocation
import com.sieve.queue.core.PreparedOutput
import com.sieve.queue.core.QueueJob
import com.sieve.queue.service.OutputLocationProvider
import com.sieve.storage.naming.CollisionResolver
import com.sieve.storage.naming.FilenameSanitizer
import com.sieve.storage.naming.MimeMapper
import com.sieve.storage.naming.ProducedFiles
import com.sieve.storage.naming.StoragePaths
import com.sieve.storage.sink.DestinationSink
import com.sieve.storage.sink.OutputTarget

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

    override suspend fun finalize(job: QueueJob, prepared: PreparedOutput): FinalLocation {
        val leaves = fs.listLeafNames(prepared.workDir)
        val classified = ProducedFiles.classify(leaves)
            ?: throw IllegalStateException("job ${job.id} produced no real output files")

        // Ordered: primary first so its resolved name drives the FinalLocation.
        val orderedLeaves = buildList {
            add(classified.primary)
            addAll(classified.sidecars)
        }

        val sink = selector.select(job)
        val dirLabel = job.output.outputDirLabel.takeIf { it.isNotBlank() }

        // Sanitize each leaf for the destination volume (may be exFAT even if work dir is ext4).
        val sanitized = orderedLeaves.map { FilenameSanitizer.sanitize(it) }

        // Grouped collision resolution against the destination's existing names.
        val existing = sink.existingNames(dirLabel)
        val finalNames = CollisionResolver.resolveGroup(sanitized, existing)

        val written = mutableListOf<OutputTarget>()
        try {
            for (i in orderedLeaves.indices) {
                val sourceLeaf = orderedLeaves[i]
                val destName = finalNames[i]
                val mime = MimeMapper.mimeOf(destName)
                fs.openRead(prepared.workDir, sourceLeaf).use { input ->
                    written += sink.write(dirLabel, destName, mime, input)
                }
            }
            written.forEach { sink.commit(it) } // all copies succeeded -> publish
        } catch (t: Throwable) {
            // All-or-nothing: roll back everything written this pass, keep work dir for retry.
            written.forEach { runCatching { sink.deletePending(it) } }
            throw t
        }

        // Happy path: finalize subsumes discard.
        if (fs.exists(prepared.workDir)) fs.deleteRecursively(prepared.workDir)

        val primaryTarget = written.first()
        return FinalLocation(displayPath = primaryTarget.relativeDisplay, uri = primaryTarget.uri)
    }

    override suspend fun discard(job: QueueJob, prepared: PreparedOutput) {
        if (fs.exists(prepared.workDir)) fs.deleteRecursively(prepared.workDir)
    }
}
