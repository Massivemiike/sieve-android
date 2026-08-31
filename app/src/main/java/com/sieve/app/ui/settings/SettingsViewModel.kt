package com.sieve.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sieve.app.di.AppGraph
import com.sieve.app.settings.AppPrefs
import com.sieve.app.settings.AppSettings
import com.sieve.app.ui.theme.ThemeMode
import com.sieve.engine.repo.YtDlpEngine
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
        extra.value = extra.value.copy(updating = true, updateMessage = null)
        val msg = runCatching { engine.doUpdate() }.map { "Engine updated" }.getOrElse { "Update failed" }
        extra.value = extra.value.copy(updating = false, updateMessage = msg, engineVersion = engine.version())
    }

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
        fun from(): SettingsViewModel = SettingsViewModel(AppGraph.appSettings, AppGraph.storageSettings, AppGraph.engine)
    }
}
