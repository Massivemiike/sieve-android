package com.sieve.app.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sieve.app.di.AppGraph
import com.sieve.app.settings.NetworkSettings
import com.sieve.engine.args.DownloadArgsOptions
import com.sieve.engine.args.EngineSettings
import com.sieve.engine.args.YtdlpArgs
import com.sieve.engine.model.VideoInfo
import com.sieve.engine.parse.YtdlpErrors
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
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

data class DownloadUiState(
    val url: String = "",
    val analyzing: Boolean = false,
    val analyzed: VideoInfo? = null,
    /** Humanized analyze failure: [error] is the headline, [errorHint] the actionable second line. */
    val error: String? = null,
    val errorHint: String? = null,
    val selectedPresetId: String = DownloadPresets.DEFAULT_ID,
    val presets: List<DownloadPreset> = DownloadPresets.ALL,
) {
    val canDownload: Boolean get() = url.isNotBlank()
    val hostname: String? get() = url.trim().takeIf { it.isNotEmpty() }
        ?.let { Regex("^\\w+://([^/]+)").find(it)?.groupValues?.get(1) }
}

/** Global yt-dlp settings with the desktop's defaults (4 parallel fragments); the caller layers user settings on top. */
internal fun defaultEngineSettings() = EngineSettings(concurrentFragments = EngineSettings.DEFAULT_CONCURRENT_FRAGMENTS)

/**
 * Drives the Download screen. `enqueue`/`engineSettings`/`outputDirLabel` are injected so the model
 * is unit-testable without the Android graph; [from] wires the production instance.
 */
class DownloadViewModel(
    private val engine: YtDlpEngine,
    private val enqueue: (QueueJob) -> Unit,
    private val engineSettings: suspend () -> EngineSettings = { defaultEngineSettings() },
    // Blank = the sink's own root (MediaStore: Download/Sieve). A non-blank value becomes a
    // SUBFOLDER under that root — passing "Download/Sieve" here created Download/Sieve/Download_Sieve.
    private val outputDirLabel: suspend () -> String = { "" },
    /** The Settings speed limit (`--limit-rate`), already normalised; null = unlimited. */
    private val speedLimit: suspend () -> String? = { null },
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    // The preset the screen starts on, and where a pick is remembered. Like the desktop app (NewDownload.tsx
    // starts on `lastPreset || defaultPreset`), the last preset you chose is the one you start on next time.
    initialPresetId: String = DownloadPresets.DEFAULT_ID,
    private val rememberPreset: suspend (String) -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(DownloadUiState(selectedPresetId = DownloadPresets.byId(initialPresetId).id))
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    fun onUrlChange(url: String) {
        _state.value = _state.value.copy(url = url, error = null, errorHint = null)
    }

    fun selectPreset(id: String) {
        val preset = DownloadPresets.ALL.firstOrNull { it.id == id } ?: return
        _state.value = _state.value.copy(selectedPresetId = preset.id)
        viewModelScope.launch { runCatching { rememberPreset(preset.id) } }
    }

    /** Clears the link and analysis; the chosen preset stays (the desktop never resets it either). */
    fun clear() {
        _state.value = DownloadUiState(selectedPresetId = _state.value.selectedPresetId)
    }

    fun analyze() {
        val url = _state.value.url.trim()
        if (url.isEmpty() || _state.value.analyzing) return
        _state.value = _state.value.copy(analyzing = true, error = null, errorHint = null)
        viewModelScope.launch {
            // The cookies file is anonymous-first inside the engine: it is only tried when the site asks for a login.
            val cookies = engineSettings().cookiesFile.ifBlank { null }
            when (val outcome = engine.analyze(url, null, cookies)) {
                is AnalyzeOutcome.Success ->
                    _state.value = _state.value.copy(analyzing = false, analyzed = outcome.info, error = null, errorHint = null)
                is AnalyzeOutcome.Failure -> {
                    val h = YtdlpErrors.humanize(outcome.message)
                    _state.value = _state.value.copy(analyzing = false, error = h.message, errorHint = h.hint)
                }
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
                // Embed metadata + cover art on every preset, as the desktop form does by default.
                toggleOpts = DownloadArgsOptions.DEFAULT_TOGGLES,
                speedLimit = speedLimit(),
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
                defaultEngineSettings().copy(
                    proxy = NetworkSettings.normalizeProxy(p.proxy).orEmpty(),
                    userAgent = NetworkSettings.normalizeUserAgent(p.userAgent).orEmpty(),
                    // A cookies file that has gone missing would make yt-dlp error out; just don't pass it.
                    cookiesFile = p.cookiesFileUri?.takeIf { File(it).isFile }.orEmpty(),
                )
            },
            speedLimit = { NetworkSettings.normalizeSpeedLimit(AppGraph.appSettings.flow.first().speedLimit) },
            outputDirLabel = { AppGraph.storageSettings.prefs.first().outputDirLabelDefault ?: "" },
            // The stored value is already in DataStore's memory (AppGraph.init read it), so this returns at once.
            initialPresetId = runCatching { runBlocking { AppGraph.appSettings.flow.first().defaultPresetId } }
                .getOrDefault(DownloadPresets.DEFAULT_ID),
            rememberPreset = { AppGraph.appSettings.setDefaultPreset(it) },
        )
    }
}
