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
 *  - exit code 1 (what yt-dlp ends with when a download fails, "[Errno 36] File name too long" included) on a row whose
 *    stored template and title prove the old naming overflowed the file-name limit
 *    ([ArgReconciler.overflowedFileName]) says that, and that it is fixed: Retry clamps the title at spawn;
 *  - the same on a Facebook row whose title overflowed once yt-dlp's part-file suffix is counted at the length it has there
 *    ([ArgReconciler.likelyOverflowedFileName]) says it MAY have, and that it is fixed: it is an estimate, not a proof;
 *  - any other says that yt-dlp stopped without a reason, with its exit code, and that Retry may work.
 * Anything else, including text that merely mentions "exited", is not touched.
 *
 * What the title verdicts cannot tell (all rare, and each still ends in true advice, Retry):
 *  - a row failed by 1.0.4 itself that kept the old stored template (the clamp is applied when yt-dlp is spawned, not stored)
 *    and ended with no output at all reads like one failed by 1.0.3;
 *  - a playlist row's title is the playlist's, not its entries';
 *  - rows failed by 1.0.3 say "yt-dlp exited 1" for every cause, so a long title is a likely, not a certain, reason.
 */
object FailureText {
    /** The whole text and nothing more: [com.sieve.queue.service.JobDriver]'s fallback for a failed run with no output. */
    private val OPAQUE_EXIT = Regex("yt-dlp exited (-?\\d+)")

    /**
     * yt-dlp's exit code for a download that failed on an error, and so for the file name that was too long. Any other code
     * (a killed process, 137) is not what v1.0.3 saw for that failure, so it is not explained by the title.
     */
    private const val DOWNLOAD_ERROR_EXIT = 1

    private val TITLE_TOO_LONG = HumanError(ErrorKind.DISK, "The title made the file name too long", "Fixed, so Retry will work.")

    private val TITLE_MAY_BE_TOO_LONG =
        HumanError(ErrorKind.DISK, "The title may have made the file name too long", "Fixed, so Retry should work.")

    fun humanize(job: QueueJob): HumanError {
        val exitCode = opaqueExitCode(job) ?: return YtdlpErrors.humanize(job.error)
        if (exitCode.toIntOrNull() == DOWNLOAD_ERROR_EXIT) {
            val template = job.output.outputTemplate
            if (ArgReconciler.overflowedFileName(template, job.title)) return TITLE_TOO_LONG
            if (ArgReconciler.likelyOverflowedFileName(template, job.title, job.site)) return TITLE_MAY_BE_TOO_LONG
        }
        return HumanError(ErrorKind.OTHER, "yt-dlp stopped without reporting a reason (exit $exitCode)", "Retry may work.")
    }

    /** [humanize] as the one line the UI shows: "message — hint". */
    fun text(job: QueueJob): String = YtdlpErrors.format(humanize(job))

    private fun opaqueExitCode(job: QueueJob): String? =
        if (job.kind != JobKind.DOWNLOAD) null else job.error?.let { OPAQUE_EXIT.matchEntire(it)?.groupValues?.get(1) }
}
