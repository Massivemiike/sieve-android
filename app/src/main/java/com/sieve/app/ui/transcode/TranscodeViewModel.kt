package com.sieve.app.ui.transcode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sieve.app.di.AppGraph
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.transcode.args.ArgFinalizer
import com.sieve.transcode.args.BuilderEncoder
import com.sieve.transcode.args.FfmpegArgs
import com.sieve.transcode.args.FinalizeOptions
import com.sieve.transcode.catalog.TranscodePresets
import com.sieve.transcode.detect.EncoderDetector
import com.sieve.transcode.detect.EncoderOption
import com.sieve.transcode.model.PresetCategory
import com.sieve.transcode.model.TranscodePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

enum class TranscodeMode { SINGLE, BATCH }

data class SourceInput(val uri: String, val name: String, val durationSec: Double?)

data class CategoryTab(val category: PresetCategory?, val label: String, val count: Int)

data class TranscodeUiState(
    val mode: TranscodeMode = TranscodeMode.SINGLE,
    val encoders: List<EncoderOption> = emptyList(),
    val activeEncoderId: String = "cpu",
    val selectedCategory: PresetCategory? = null, // null = All
    val selectedPresetId: String = "h264-1080",
    val crf: Int = 23,
    val normalize: Boolean = false,
    val sources: List<SourceInput> = emptyList(),
) {
    val categoryTabs: List<CategoryTab>
        get() = buildList {
            add(CategoryTab(null, "All", TranscodePresets.all.size))
            PresetCategory.entries.forEach { c ->
                val n = TranscodePresets.all.count { it.category == c }
                if (n > 0) add(CategoryTab(c, c.label, n))
            }
        }

    val visiblePresets: List<TranscodePreset>
        get() = if (selectedCategory == null) TranscodePresets.all
        else TranscodePresets.all.filter { it.category == selectedCategory }

    val canStart: Boolean get() = sources.isNotEmpty()
}

class TranscodeViewModel(
    private val detector: EncoderDetector,
    private val enqueue: (QueueJob) -> Unit,
    private val materialize: suspend (uri: String, name: String) -> String,
    private val coreCount: () -> Int = { Runtime.getRuntime().availableProcessors() },
    private val outputDirLabel: suspend () -> String = { "Download/Sieve" },
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val _state = MutableStateFlow(TranscodeUiState())
    val state: StateFlow<TranscodeUiState> = _state.asStateFlow()

    init { detect() }

    fun detect() {
        val result = detector.detect(_state.value.activeEncoderId.takeIf { it != "cpu" })
        _state.value = _state.value.copy(encoders = result.encoders, activeEncoderId = result.selected)
    }

    fun setMode(mode: TranscodeMode) { _state.value = _state.value.copy(mode = mode) }
    fun setEncoder(id: String) { _state.value = _state.value.copy(activeEncoderId = id) }
    fun selectCategory(category: PresetCategory?) { _state.value = _state.value.copy(selectedCategory = category) }
    fun selectPreset(id: String) { _state.value = _state.value.copy(selectedPresetId = id) }
    fun setCrf(crf: Int) { _state.value = _state.value.copy(crf = crf.coerceIn(14, 32)) }
    fun setNormalize(on: Boolean) { _state.value = _state.value.copy(normalize = on) }
    fun addSource(source: SourceInput) { _state.value = _state.value.copy(sources = _state.value.sources + source) }
    fun removeSource(uri: String) { _state.value = _state.value.copy(sources = _state.value.sources.filterNot { it.uri == uri }) }

    fun start() {
        val s = _state.value
        if (s.sources.isEmpty()) return
        val preset = TranscodePresets.all.first { it.id == s.selectedPresetId }
        val encoder = if (s.activeEncoderId.startsWith("hw")) BuilderEncoder.HARDWARE else BuilderEncoder.SOFTWARE
        val sources = if (s.mode == TranscodeMode.SINGLE) s.sources.take(1) else s.sources
        viewModelScope.launch {
            val dirLabel = outputDirLabel()
            val threads = maxOf(1, coreCount() - 2)
            for (src in sources) {
                val workPath = materialize(src.uri, src.name)
                val base = FfmpegArgs.build(preset.id, encoder, durationSec = src.durationSec ?: 0.0)
                val presetArgs = ArgFinalizer.finalize(
                    base,
                    FinalizeOptions(
                        requestedThreads = threads,
                        emitThreads = encoder == BuilderEncoder.SOFTWARE,
                        crfOverride = s.crf,
                        normalizeAudio = s.normalize,
                    ),
                )
                val outName = src.name.substringBeforeLast('.', src.name) + "." + preset.ext
                enqueue(
                    QueueJob(
                        id = idGen(),
                        spec = JobSpec.Transcode(
                            inputPath = workPath,
                            presetArgs = presetArgs,
                            totalDurationSec = src.durationSec,
                            usedHardwareEncoder = encoder == BuilderEncoder.HARDWARE,
                        ),
                        output = OutputRequest(dirLabel, outName),
                        title = outName,
                        format = preset.name,
                        durationSec = src.durationSec?.toLong(),
                    ),
                )
            }
            _state.value = _state.value.copy(sources = emptyList())
        }
    }

    companion object {
        fun from(): TranscodeViewModel = TranscodeViewModel(
            detector = AppGraph.encoderDetector,
            enqueue = { AppGraph.queue.enqueue(it) },
            materialize = { uri, name -> AppGraph.materializeSource(uri, name) },
            outputDirLabel = { AppGraph.storageSettings.prefs.first().outputDirLabelDefault ?: "Download/Sieve" },
        )
    }
}
