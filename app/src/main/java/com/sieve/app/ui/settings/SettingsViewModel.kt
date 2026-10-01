package com.sieve.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sieve.app.di.AppGraph
import com.sieve.app.settings.AppPrefs
import com.sieve.app.settings.AppSettings
import com.sieve.app.ui.theme.ThemeMode
import com.sieve.engine.repo.YtDlpEngine
import com.sieve.queue.core.hasActiveDownload
import com.sieve.storage.settings.StorageSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val app: AppPrefs = AppPrefs(),
    val outputTreeUri: String? = null,
    val engineVersion: String? = null,
    val updating: Boolean = false,
    val updateMessage: String? = null,
)

class SettingsViewModel(
    private val appSettings: AppSettings,
    private val storageSettings: StorageSettings,
    private val engine: YtDlpEngine,
    /** True while a yt-dlp download is preparing/running. The updater replaces yt-dlp in place, so no update then. */
    private val downloadActive: () -> Boolean = { false },
) : ViewModel() {

    private val extra = MutableStateFlow(SettingsUiState())

    val state: StateFlow<SettingsUiState> =
        combine(appSettings.flow, storageSettings.prefs, extra) { app, storage, e ->
            e.copy(app = app, outputTreeUri = storage.outputTreeUri)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init { launch { extra.value = extra.value.copy(engineVersion = engine.version()) } }

    fun setThemeMode(m: ThemeMode) = launch { appSettings.setThemeMode(m) }
    fun setAccent(hex: String) = launch { appSettings.setAccentHex(hex) }
    fun setDefaultPreset(id: String) = launch { appSettings.setDefaultPreset(id) }
    fun setMaxDownloads(n: Int) = launch { appSettings.setMaxDownloads(n) }
    fun setMaxTranscodes(n: Int) = launch { appSettings.setMaxTranscodes(n) }
    fun setProxy(v: String?) = launch { appSettings.setProxy(v) }
    fun setUserAgent(v: String?) = launch { appSettings.setUserAgent(v) }
    fun setSpeedLimit(v: String?) = launch { appSettings.setSpeedLimit(v) }
    fun setCookiesFile(v: String?) = launch { appSettings.setCookiesFileUri(v) }
    fun setOutputTree(uri: String?) = launch { storageSettings.setOutputTree(uri) }

    fun updateEngine() = launch {
        if (downloadActive()) {
            extra.value = extra.value.copy(updateMessage = UPDATE_BLOCKED_MESSAGE)
            return@launch
        }
        extra.value = extra.value.copy(updating = true, updateMessage = null)
        // doUpdate() reports failure as UpdateResult(ok = false) rather than throwing.
        val msg = runCatching { engine.doUpdate() }.fold(
            onSuccess = { if (it.ok) "Engine updated" else failureMessage(it.output) },
            onFailure = { failureMessage(it.message) },
        )
        extra.value = extra.value.copy(updating = false, updateMessage = msg, engineVersion = engine.version())
    }

    private fun failureMessage(detail: String?): String =
        detail?.trim()?.takeIf { it.isNotEmpty() }?.let { "Update failed: ${it.take(120)}" } ?: "Update failed"

    fun reset() = launch {
        appSettings.setThemeMode(ThemeMode.DARK)
        appSettings.setAccentHex(AppPrefs.DEFAULT_ACCENT)
        appSettings.setDefaultPreset("best-video")
        appSettings.setMaxDownloads(3)
        appSettings.setMaxTranscodes(1)
        appSettings.setProxy(null); appSettings.setUserAgent(null); appSettings.setSpeedLimit(null)
    }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

    companion object {
        const val UPDATE_BLOCKED_MESSAGE = "Wait for downloads to finish before updating yt-dlp."

        fun from(): SettingsViewModel = SettingsViewModel(
            AppGraph.appSettings, AppGraph.storageSettings, AppGraph.engine,
            downloadActive = { AppGraph.queue.state.value.hasActiveDownload() },
        )
    }
}
