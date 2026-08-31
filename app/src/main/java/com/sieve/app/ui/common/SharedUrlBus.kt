package com.sieve.app.ui.common

import android.content.Intent
import android.net.Uri
import com.sieve.engine.args.DownloadArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A process-wide hand-off for a URL shared into the app (ACTION_SEND / ACTION_VIEW). MainActivity
 * publishes; the Download screen consumes it into its field.
 */
object SharedUrlBus {
    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    fun publish(url: String) { _url.value = url }
    fun consume() { _url.value = null }

    /** Pull a valid media URL out of a share/view intent, or null. Pure — unit-testable. */
    fun extract(action: String?, type: String?, extraText: String?, data: Uri?): String? {
        val candidate = when (action) {
            Intent.ACTION_SEND -> extraText?.takeIf { type == null || type.startsWith("text/") }
            Intent.ACTION_VIEW -> data?.toString()
            else -> null
        }?.trim().orEmpty()
        // A shared message may wrap the URL in text; grab the first http(s) token.
        val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(candidate)?.value ?: candidate
        return url.takeIf { it.isNotBlank() && DownloadArgs.isValidUrl(it) }
    }

    fun extract(intent: Intent?): String? =
        intent?.let { extract(it.action, it.type, it.getStringExtra(Intent.EXTRA_TEXT), it.data) }
}
