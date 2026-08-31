package com.sieve.queue.persist

import com.sieve.data.db.DownloadTaskEntity
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.UnifiedProgress

/**
 * Entity ↔ domain bridge. Lives in `:queue` (not `:data`) because it references BOTH the domain
 * ([QueueJob], in `:queue.core`) and the Room entities (in `:data`) — putting it in `:data` would
 * make `:data` depend on `:queue`, which already depends on `:data`.
 *
 * The persist projection mirrors the desktop: RUNNING/PREPARING downgrade to QUEUED on write and
 * progress is stored as 0 or 1 only; transient logs/speed history are dropped. PAUSED persists
 * faithfully — the reducer's `Rehydrate` decides resume at load time. Status is the enum `name`
 * (the entity column is a plain String).
 */
object TaskMapping {
    private val PROJECT_TO_QUEUED = setOf(DownloadStatus.RUNNING, DownloadStatus.PREPARING)

    fun toEntity(job: QueueJob): DownloadTaskEntity {
        val persistedStatus = if (job.status in PROJECT_TO_QUEUED) DownloadStatus.QUEUED else job.status
        val persistedProgress = if (persistedStatus == DownloadStatus.COMPLETED) 1f else 0f
        val d = job.spec as? JobSpec.Download
        val t = job.spec as? JobSpec.Transcode
        return DownloadTaskEntity(
            id = job.id, position = job.position,
            url = d?.url ?: "", kind = job.kind.name, status = persistedStatus.name,
            title = job.title, channel = job.channel, site = job.site, format = job.format,
            durationSec = job.durationSec, sizeBytes = job.progress.sizeBytes,
            progress = persistedProgress, thumbnailUrl = job.thumbnailUrl,
            args = d?.engineArgs ?: emptyList(),
            inputPath = t?.inputPath, presetArgs = t?.presetArgs,
            totalDurationSec = t?.totalDurationSec, usedHardwareEncoder = t?.usedHardwareEncoder ?: false,
            outputDirLabel = job.output.outputDirLabel, outputTemplate = job.output.outputTemplate,
            attempt = job.attempt, nextEligibleAt = job.nextEligibleAt, error = job.error, filePath = job.filePath,
            notes = job.notes, colorTag = job.colorTag, pinned = job.pinned, completedAt = job.completedAt,
        )
    }

    fun toDomain(e: DownloadTaskEntity): QueueJob {
        val spec = if (e.kind == "TRANSCODE")
            JobSpec.Transcode(e.inputPath ?: "", e.presetArgs ?: emptyList(), e.totalDurationSec, e.usedHardwareEncoder)
        else JobSpec.Download(e.url, e.args)
        return QueueJob(
            id = e.id, spec = spec, output = OutputRequest(e.outputDirLabel, e.outputTemplate),
            status = runCatching { DownloadStatus.valueOf(e.status) }.getOrDefault(DownloadStatus.QUEUED),
            progress = UnifiedProgress(fraction = if (e.progress > 0f) e.progress else null, sizeBytes = e.sizeBytes),
            attempt = e.attempt, nextEligibleAt = e.nextEligibleAt, position = e.position, pinned = e.pinned,
            error = e.error, filePath = e.filePath, title = e.title, channel = e.channel,
            site = e.site, format = e.format, durationSec = e.durationSec, thumbnailUrl = e.thumbnailUrl,
            colorTag = e.colorTag, notes = e.notes, completedAt = e.completedAt,
        )
    }
}
