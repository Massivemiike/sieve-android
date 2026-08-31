package com.sieve.queue.core

/** Kind-agnostic lifecycle state persisted to Room. Mirrors desktop's DownloadState,
 *  collapsed so ONE reducer drives both yt-dlp and ffmpeg jobs. */
enum class DownloadStatus(val isTerminal: Boolean) {
    QUEUED(false),
    PREPARING(false),   // OutputLocationProvider.prepare() in flight
    RUNNING(false),     // desktop 'downloading' AND 'postprocess' both land here
    PAUSED(false),      // desktop 'paused': killed process, row + partial retained
    COMPLETED(true),    // desktop 'done'
    FAILED(true),       // desktop 'error'
    CANCELLED(true);    // no desktop analog (desktop deletes the row instead)
}

enum class JobKind { DOWNLOAD, TRANSCODE }

/** Fine-grained UI phase, orthogonal to DownloadStatus. */
enum class Phase { QUEUED, PREPARING, DOWNLOADING, POSTPROCESS, TRANSCODING, PAUSED, DONE, FAILED }

sealed interface JobSpec {
    /** Drives YtDlpEngine.download(id, url, args). engineArgs carry EVERYTHING except the
     *  physical output location (-P/-o are injected at spawn from the OutputLocationProvider). */
    data class Download(val url: String, val engineArgs: List<String>) : JobSpec

    /** Drives FfmpegRunner.run(job). outputPath is filled at spawn from the seam. */
    data class Transcode(
        val inputPath: String,
        val presetArgs: List<String>,
        val totalDurationSec: Double?,
        val usedHardwareEncoder: Boolean,
    ) : JobSpec
}

/** Abstract, location-free description of where output goes. Resolved just-in-time
 *  by OutputLocationProvider (plan #4). Persisted; never contains a content:// URI. */
data class OutputRequest(
    val outputDirLabel: String,     // e.g. "Downloads/Sieve" — display + seam hint, NOT a path
    val outputTemplate: String,     // yt-dlp -o template or ffmpeg filename
)

data class UnifiedProgress(
    val fraction: Float? = null,    // null = indeterminate (spinner)
    val speed: String? = null,      // "1.17MiB/s"
    val eta: String? = null,        // "00:42"
    val phase: Phase = Phase.QUEUED,
    val sizeBytes: Long? = null,    // observed total size, when known
    val fragmentIndex: Int? = null,
    val fragmentCount: Int? = null,
)

enum class CancelReason { PAUSE, USER_CANCEL, SHUTDOWN }

/** Immutable snapshot of one queue row. `logsTail`/`speedHistory` are transient (not persisted). */
data class QueueJob(
    val id: String,
    val spec: JobSpec,
    val output: OutputRequest,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: UnifiedProgress = UnifiedProgress(),
    val attempt: Int = 0,
    val position: Long = 0L,            // persisted queue order (desktop used array index)
    val pinned: Boolean = false,        // view-only float; does NOT affect run order
    val cancelReason: CancelReason? = null,
    val error: String? = null,
    val filePath: String? = null,       // final location, set on finalize
    val title: String = "",
    val channel: String = "",
    val site: String = "Unknown",
    val format: String = "",
    val durationSec: Long? = null,
    val thumbnailUrl: String = "",
    val colorTag: String? = null,
    val notes: String? = null,
    val completedAt: Long? = null,
    val logsTail: List<String> = emptyList(),
    val speedHistory: List<Float> = emptyList(),
) {
    val kind: JobKind get() = when (spec) {
        is JobSpec.Download -> JobKind.DOWNLOAD
        is JobSpec.Transcode -> JobKind.TRANSCODE
    }
    /** yt-dlp -c continues a partial; ffmpeg cannot resume a partial output. */
    val resumable: Boolean get() = spec is JobSpec.Download
}

/** Normalized event algebra the reducer consumes. Drivers (Task 11) collapse
 *  EngineEvent + TranscodeEvent into these. The reducer NEVER sees the raw module events. */
sealed interface JobSignal {
    val jobId: String
    data class Progress(override val jobId: String, val progress: UnifiedProgress) : JobSignal
    data class Log(override val jobId: String, val line: String, val isError: Boolean, val filePath: String? = null) : JobSignal
    data class Terminal(override val jobId: String, val outcome: Outcome) : JobSignal
}

sealed interface Outcome {
    data object Succeeded : Outcome
    data class Failed(val info: FailureInfo) : Outcome
    data class Cancelled(val reason: CancelReason) : Outcome
}

data class FailureInfo(
    val message: String,
    val exitCode: Int? = null,
    val stderrTail: String? = null,
)

/** Returned by OutputLocationProvider.prepare(); work path is STABLE per job id
 *  so a resumed yt-dlp -c finds its own .part. */
data class PreparedOutput(val workDir: String, val workFileTemplate: String)
data class FinalLocation(val displayPath: String, val uri: String?)
