package com.sieve.app.ui.common

import com.sieve.engine.parse.YtdlpErrors
import com.sieve.queue.core.FailureText
import com.sieve.queue.core.QueueJob

/**
 * Turns a raw yt-dlp error into a short, actionable one-liner ("message — hint"). The rule table
 * lives in :engine ([YtdlpErrors], a port of the desktop `ytdlpErrors.ts`); this is only the
 * string-formatting adapter the UI uses. Callers that want the message and hint separately (the
 * Download screen's error banner) use [YtdlpErrors.humanize] directly.
 */
object ErrorHumanizer {
    fun humanize(raw: String?): String = YtdlpErrors.format(YtdlpErrors.humanize(raw))

    /** A failed queue row's message. Not [humanize] of its text: "yt-dlp exited N" is explained from the row's title and template ([FailureText]). */
    fun humanize(job: QueueJob): String = FailureText.text(job)
}
