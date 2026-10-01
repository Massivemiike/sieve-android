package com.sieve.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sieve.app.di.AppGraph
import com.sieve.app.settings.AppPrefs
import com.sieve.app.settings.AppSettings
import com.sieve.app.settings.CookiesInfo
import com.sieve.app.settings.CookiesStore
import com.sieve.app.settings.NetworkSettings
import com.sieve.app.ui.theme.ThemeMode
import com.sieve.engine.repo.YtDlpEngine
import com.sieve.queue.core.hasActiveDownload
import com.sieve.storage.settings.StorageSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val app: AppPrefs = AppPrefs(),
    val outputTreeUri: String? = null,
    val engineVersion: String? = null,
    val updating: Boolean = false,
    val updateMessage: String? = null,
    /** True when [updateMessage] is a refusal or a failure (shown in the error colour), false for "Engine updated". */
    val updateMessageIsError: Boolean = false,
    /** The imported cookies.txt, null when none is set. */
    val cookies: CookiesInfo? = null,
    /** Why the last cookies import failed; cleared by the next import or by dismissing it. */
    val cookiesMessage: String? = null,
)

class SettingsViewModel(
    private val appSettings: AppSettings,
    private val storageSettings: StorageSettings,
    private val engine: YtDlpEngine,
    /** True while a yt-dlp download is preparing/running. The updater replaces yt-dlp in place, so no update then. */
    private val downloadActive: () -> Boolean = { false },
    private val cookiesStore: CookiesStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val extra = MutableStateFlow(SettingsUiState())

    val state: StateFlow<SettingsUiState> =
        combine(appSettings.flow, storageSettings.prefs, extra) { app, storage, e ->
            // Only report cookies while Settings still points at them (a dangling path is no cookies).
            e.copy(app = app, outputTreeUri = storage.outputTreeUri, cookies = e.cookies.takeIf { app.cookiesFileUri != null })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        // Each launch computes its value FIRST and then merges it atomically: `extra.value = extra.value.copy(x = suspendingCall())`
        // reads the receiver before it suspends, so whichever read finished last would overwrite the other's field.
        launch { val v = engine.version(); extra.update { it.copy(engineVersion = v) } }
        launch { val c = withContext(io) { cookiesStore.info() }; extra.update { it.copy(cookies = c) } }
    }

    fun setThemeMode(m: ThemeMode) = launch { appSettings.setThemeMode(m) }
    fun setAccent(hex: String) = launch { appSettings.setAccentHex(hex) }
    fun setDefaultPreset(id: String) = launch { appSettings.setDefaultPreset(id) }
    fun setMaxDownloads(n: Int) = launch { appSettings.setMaxDownloads(n) }
    fun setMaxTranscodes(n: Int) = launch { appSettings.setMaxTranscodes(n) }
    // The dialogs only offer Save for valid text; these re-check so a bad value can never reach the args.
    fun setProxy(v: String?) = launch {
        if (NetworkSettings.proxyError(v.orEmpty()) == null) appSettings.setProxy(NetworkSettings.normalizeProxy(v))
    }
    fun setUserAgent(v: String?) = launch {
        if (NetworkSettings.userAgentError(v.orEmpty()) == null) appSettings.setUserAgent(NetworkSettings.normalizeUserAgent(v))
    }
    fun setSpeedLimit(v: String?) = launch {
        if (NetworkSettings.speedLimitError(v.orEmpty()) == null) appSettings.setSpeedLimit(NetworkSettings.normalizeSpeedLimit(v))
    }

    /** Copies the picked cookies.txt into the app and points Settings at the copy. */
    fun importCookies(uri: String?) = launch {
        if (uri == null) return@launch
        when (val r = withContext(io) { cookiesStore.importFrom(uri) }) {
            is CookiesStore.Result.Imported -> {
                appSettings.setCookiesFileUri(cookiesStore.file.absolutePath)
                extra.value = extra.value.copy(cookies = r.info, cookiesMessage = null)
            }
            CookiesStore.Result.NotCookies ->
                extra.value = extra.value.copy(cookiesMessage = "That doesn't look like a cookies.txt (Netscape format).")
            CookiesStore.Result.Unreadable ->
                extra.value = extra.value.copy(cookiesMessage = "Couldn't read that file.")
        }
    }

    fun removeCookies() = launch {
        withContext(io) { cookiesStore.remove() }
        appSettings.setCookiesFileUri(null)
        extra.value = extra.value.copy(cookies = null, cookiesMessage = null)
    }

    fun dismissCookiesMessage() { extra.value = extra.value.copy(cookiesMessage = null) }
    fun setOutputTree(uri: String?) = launch { storageSettings.setOutputTree(uri) }

    fun updateEngine() = launch {
        if (downloadActive()) {
            extra.value = extra.value.copy(updateMessage = UPDATE_BLOCKED_MESSAGE, updateMessageIsError = true)
            return@launch
        }
        extra.value = extra.value.copy(updating = true, updateMessage = null, updateMessageIsError = false)
        // doUpdate() reports failure as UpdateResult(ok = false) rather than throwing. null = it worked.
        val failure = runCatching { engine.doUpdate() }.fold(
            onSuccess = { if (it.ok) null else failureMessage(it.output) },
            onFailure = { failureMessage(it.message) },
        )
        val v = engine.version()
        extra.update {
            it.copy(updating = false, updateMessage = failure ?: "Engine updated", updateMessageIsError = failure != null, engineVersion = v)
        }
    }

    fun dismissUpdateMessage() { extra.value = extra.value.copy(updateMessage = null, updateMessageIsError = false) }

    private fun failureMessage(detail: String?): String =
        detail?.trim()?.takeIf { it.isNotEmpty() }?.let { "Update failed: ${it.take(120)}" } ?: "Update failed"

    fun reset() = launch {
        appSettings.setThemeMode(ThemeMode.DARK)
        appSettings.setAccentHex(AppPrefs.DEFAULT_ACCENT)
        appSettings.setDefaultPreset("best-video")
        appSettings.setMaxDownloads(3)
        appSettings.setMaxTranscodes(1)
        appSettings.setProxy(null); appSettings.setUserAgent(null); appSettings.setSpeedLimit(null)
        withContext(io) { cookiesStore.remove() }
        appSettings.setCookiesFileUri(null)
        extra.value = extra.value.copy(cookies = null, cookiesMessage = null)
    }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

    companion object {
        const val UPDATE_BLOCKED_MESSAGE = "Wait for downloads to finish before updating yt-dlp."

        fun from(): SettingsViewModel = SettingsViewModel(
            AppGraph.appSettings, AppGraph.storageSettings, AppGraph.engine,
            downloadActive = { AppGraph.queue.state.value.hasActiveDownload() },
            cookiesStore = AppGraph.cookiesStore,
        )
    }
}
