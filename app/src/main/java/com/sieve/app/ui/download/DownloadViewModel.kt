package com.sieve.app.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sieve.app.di.AppGraph
import com.sieve.app.ui.common.ErrorHumanizer
import com.sieve.engine.args.DownloadArgsOptions
import com.sieve.engine.args.EngineSettings
import com.sieve.engine.args.YtdlpArgs
import com.sieve.engine.model.VideoInfo
import com.sieve.engine.repo.AnalyzeOutcome
import com.sieve.engine.repo.YtDlpEngine
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

data class DownloadUiState(
    val url: String = "",
    val analyzing: Boolean = false,
    val analyzed: VideoInfo? = null,
    val error: String? = null,
    val selectedPresetId: String = "best-video",
    val presets: List<DownloadPreset> = DownloadPresets.ALL,
) {
    val canDownload: Boolean get() = url.isNotBlank()
    val hostname: String? get() = url.trim().takeIf { it.isNotEmpty() }
        ?.let { Regex("^\\w+://([^/]+)").find(it)?.groupValues?.get(1) }
}

/**
 * Drives the Download screen. `enqueue`/`engineSettings`/`outputDirLabel` are injected so the model
 * is unit-testable without the Android graph; [from] wires the production instance.
 */
class DownloadViewModel(
    private val engine: YtDlpEngine,
    private val enqueue: (QueueJob) -> Unit,
    private val engineSettings: suspend () -> EngineSettings = { EngineSettings() },
    private val outputDirLabel: suspend () -> String = { "Download/Sieve" },
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    fun onUrlChange(url: String) {
        _state.value = _state.value.copy(url = url, error = null)
    }

    fun selectPreset(id: String) {
        _state.value = _state.value.copy(selectedPresetId = id)
    }

    fun clear() {
        _state.value = DownloadUiState()
    }

    fun analyze() {
        val url = _state.value.url.trim()
        if (url.isEmpty() || _state.value.analyzing) return
        _state.value = _state.value.copy(analyzing = true, error = null)
        viewModelScope.launch {
            when (val outcome = engine.analyze(url, null)) {
                is AnalyzeOutcome.Success ->
                    _state.value = _state.value.copy(analyzing = false, analyzed = outcome.info, error = null)
                is AnalyzeOutcome.Failure ->
                    _state.value = _state.value.copy(analyzing = false, error = ErrorHumanizer.humanize(outcome.message))
            }
        }
    }

    fun download() {
        val s = _state.value
        if (s.url.isBlank()) return
        val preset = DownloadPresets.byId(s.selectedPresetId)
        val info = s.analyzed
        viewModelScope.launch {
            val opts = DownloadArgsOptions(
                format = preset.format,
                extraArgs = preset.extraArgs.ifEmpty { null },
                audioOnly = preset.audioOnly,
            )
            val args = YtdlpArgs.build(opts, engineSettings())
            val job = QueueJob(
                id = idGen(),
                spec = JobSpec.Download(s.url.trim(), args),
                output = OutputRequest(outputDirLabel(), YtdlpArgs.DEFAULT_TEMPLATE),
                title = info?.title ?: "",
                channel = info?.displayChannel ?: "",
                site = info?.extractor ?: "Unknown",
                format = preset.label,
                thumbnailUrl = info?.thumbnail ?: "",
                durationSec = info?.duration?.toLong(),
            )
            enqueue(job)
            // reset for the next paste, keep the chosen preset
            _state.value = DownloadUiState(selectedPresetId = s.selectedPresetId)
        }
    }

    companion object {
        fun from(): DownloadViewModel = DownloadViewModel(
            engine = AppGraph.engine,
            enqueue = { AppGraph.queue.enqueue(it) },
            engineSettings = {
                val p = AppGraph.appSettings.flow.first()
                EngineSettings(
                    proxy = p.proxy ?: "",
                    userAgent = p.userAgent ?: "",
                    cookiesFile = p.cookiesFileUri ?: "",
                )
            },
            outputDirLabel = { AppGraph.storageSettings.prefs.first().outputDirLabelDefault ?: "Download/Sieve" },
        )
    }
}
