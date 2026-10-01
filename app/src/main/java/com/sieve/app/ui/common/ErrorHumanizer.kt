package com.sieve.app.ui.common

import com.sieve.engine.parse.YtdlpErrors

/**
 * Turns a raw yt-dlp error into a short, actionable one-liner ("message — hint"). The rule table
 * lives in :engine ([YtdlpErrors], a port of the desktop `ytdlpErrors.ts`); this is only the
 * string-formatting adapter the UI uses. Callers that want the message and hint separately (the
 * Download screen's error banner) use [YtdlpErrors.humanize] directly.
 */
object ErrorHumanizer {
    fun humanize(raw: String?): String = YtdlpErrors.format(YtdlpErrors.humanize(raw))
}
