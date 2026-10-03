package com.sieve.app.ui.common

import com.sieve.engine.parse.YtdlpErrors
import com.sieve.queue.core.FailureText
import com.sieve.queue.core.QueueJob

/**
 * Turns a failed queue row's error into a short, actionable one-liner ("message — hint"). The rule table
 * lives in :engine ([YtdlpErrors], a port of the desktop `ytdlpErrors.ts`) and the one text it cannot explain,
 * "yt-dlp exited N", is explained from the row's title and template ([FailureText]); this is only the
 * adapter the UI uses. It takes the row, never its text alone, because that one text depends on the row. Callers
 * that want the message and hint of a raw text separately (the Download screen's error banner) use
 * [YtdlpErrors.humanize] directly.
 */
object ErrorHumanizer {
    fun humanize(job: QueueJob): String = FailureText.text(job)
}
