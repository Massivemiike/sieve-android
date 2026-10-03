package com.sieve.queue.core

import com.sieve.engine.parse.ErrorKind
import com.sieve.engine.parse.HumanError
import com.sieve.engine.parse.YtdlpErrors

/**
 * What a FAILED row says about why, wherever the row's failure is worded for the user (the Queue row, the in-app snackbar and
 * the system notification all come here). Display only: the stored text ([QueueJob.error]) stays raw, because
 * [RetryClassifier] reads it.
 *
 * Almost always this is [YtdlpErrors], as before. The exception is the one text that says nothing: "yt-dlp exited N", which
 * [com.sieve.queue.service.JobDriver] stores when yt-dlp printed no error line, and which every row failed by v1.0.3 or older
 * carries (those versions never kept yt-dlp's own error). It is replaced by a sentence a person can act on, decided here
 * when it is shown, so nothing in the database is rewritten:
 *  - a row whose stored template and title prove the old naming overflowed the file-name limit
 *    ([ArgReconciler.overflowedFileName]) says that, and that it is fixed: Retry clamps the title at spawn;
 *  - any other says that yt-dlp stopped without a reason, with its exit code, and that Retry may work.
 * Anything else, including text that merely mentions "exited", is not touched.
 */
object FailureText {
    /** The whole text and nothing more: [com.sieve.queue.service.JobDriver]'s fallback for a failed run with no output. */
    private val OPAQUE_EXIT = Regex("yt-dlp exited (-?\\d+)")

    private val TITLE_TOO_LONG = HumanError(ErrorKind.DISK, "The title made the file name too long", "Fixed, so Retry will work.")

    fun humanize(job: QueueJob): HumanError {
        val exitCode = opaqueExitCode(job) ?: return YtdlpErrors.humanize(job.error)
        return if (ArgReconciler.overflowedFileName(job.output.outputTemplate, job.title)) {
            TITLE_TOO_LONG
        } else {
            HumanError(ErrorKind.OTHER, "yt-dlp stopped without reporting a reason (exit $exitCode)", "Retry may work.")
        }
    }

    /** [humanize] as the one line the UI shows: "message — hint". */
    fun text(job: QueueJob): String = YtdlpErrors.format(humanize(job))

    private fun opaqueExitCode(job: QueueJob): String? =
        if (job.kind != JobKind.DOWNLOAD) null else job.error?.let { OPAQUE_EXIT.matchEntire(it)?.groupValues?.get(1) }
}
