package com.sieve.app.ui.transcode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sieve.app.di.AppGraph
import com.sieve.app.ui.queue.QueueReveal
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
import com.sieve.transcode.runner.android.SourceProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /**
     * The user's own CRF. Null until they move the slider (and again once they drag it back onto the
     * preset's value), which leaves the preset's CRF untouched - the Windows `crfOverride` default.
     */
    val crfOverride: Int? = null,
    val normalize: Boolean = false,
    val sources: List<SourceInput> = emptyList(),
    /** True from a Start tap until every consumed source is queued (or failed to copy); Start is disabled meanwhile. */
    val starting: Boolean = false,
    /** Why the last Start left a source behind (it could not be read), until the next Start. */
    val startError: String? = null,
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

    val canStart: Boolean get() = sources.isNotEmpty() && !starting

    private val crfSpec: CrfSpec? get() = crfSpecOf(selectedPresetId)

    /** The selected preset's own `-crf`; null for presets with no quality knob (bitrate, audio, image, intermediates). */
    val presetCrf: Int? get() = crfSpec?.value

    /** Top of the selected preset's CRF scale (libsvtav1 / libvpx-vp9 run 0..63, x264/x265 0..51). */
    val crfMax: Int get() = crfSpec?.max ?: 51

    /** What the slider shows: the user's override, else the preset's own CRF; null when the preset has none. */
    val shownCrf: Int? get() = crfSpec?.let { (crfOverride ?: it.value).coerceIn(1, it.max) }

    /** The override that actually applies to the selected preset; null leaves its own `-crf` alone. */
    val appliedCrfOverride: Int? get() = crfSpec?.let { spec -> crfOverride?.coerceIn(1, spec.max)?.takeIf { it != spec.value } }
}

private data class CrfSpec(val value: Int, val max: Int)

/**
 * A preset's own CRF, read from the args it builds (the codec does not change the number, so software is
 * as good as hardware here). Null when the preset builds no `-crf`.
 */
private fun crfSpecOf(presetId: String): CrfSpec? {
    val args = FfmpegArgs.build(presetId, BuilderEncoder.SOFTWARE)
    val at = args.indexOf("-crf")
    if (at < 0) return null
    val value = args.getOrNull(at + 1)?.toIntOrNull() ?: return null
    val codec = args.getOrNull(args.indexOf("-c:v") + 1)
    return CrfSpec(value, if (codec == "libsvtav1" || codec == "libvpx-vp9") 63 else 51)
}

class TranscodeViewModel(
    private val detector: EncoderDetector,
    private val enqueue: (QueueJob) -> Unit,
    private val materialize: suspend (uri: String, name: String) -> String,
    private val coreCount: () -> Int = { Runtime.getRuntime().availableProcessors() },
    // Blank = the sink's own root (Download/Sieve); non-blank = a subfolder under it.
    private val outputDirLabel: suspend () -> String = { "" },
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    // The source's length in seconds (Windows gets it from ffprobe); null = unknown, so progress stays indeterminate.
    private val probeDuration: suspend (path: String) -> Double? = { null },
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
    /** Dragging the slider back onto the preset's own value clears the override, as on Windows. */
    fun setCrf(crf: Int) {
        val spec = crfSpecOf(_state.value.selectedPresetId) ?: return // this preset has no quality knob
        val v = crf.coerceIn(1, spec.max)
        _state.update { it.copy(crfOverride = v.takeIf { v != spec.value }) }
    }
    fun setNormalize(on: Boolean) { _state.value = _state.value.copy(normalize = on) }
    fun addSource(source: SourceInput) { _state.value = _state.value.copy(sources = _state.value.sources + source) }
    fun removeSource(uri: String) { _state.value = _state.value.copy(sources = _state.value.sources.filterNot { it.uri == uri }) }

    fun start() {
        val s = _state.value
        if (s.starting || s.sources.isEmpty()) return // a second tap while the first is still copying
        val preset = TranscodePresets.all.first { it.id == s.selectedPresetId }
        val encoder = if (s.activeEncoderId.startsWith("hw")) BuilderEncoder.HARDWARE else BuilderEncoder.SOFTWARE
        val sources = if (s.mode == TranscodeMode.SINGLE) s.sources.take(1) else s.sources
        val crfOverride = s.appliedCrfOverride
        _state.update { it.copy(starting = true, startError = null) } // claimed before the coroutine runs
        viewModelScope.launch {
            try {
                val dirLabel = outputDirLabel()
                val threads = maxOf(1, coreCount() - 2)
                for (src in sources) {
                    if (src !in _state.value.sources) continue // removed by the user while an earlier one was copying
                    val workPath = try {
                        materialize(src.uri, src.name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // It stays listed so the user can try again; the others still go.
                        _state.update { it.copy(startError = "Couldn't read ${src.name} - it is still listed, try again.") }
                        continue
                    }
                    val durationSec = src.durationSec ?: probeDuration(workPath)
                    val base = FfmpegArgs.build(preset.id, encoder, durationSec = durationSec ?: 0.0)
                    val presetArgs = ArgFinalizer.finalize(
                        base,
                        FinalizeOptions(
                            requestedThreads = threads,
                            emitThreads = encoder == BuilderEncoder.SOFTWARE,
                            crfOverride = crfOverride,
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
                                totalDurationSec = durationSec,
                                usedHardwareEncoder = encoder == BuilderEncoder.HARDWARE,
                            ),
                            output = OutputRequest(dirLabel, outName),
                            title = outName,
                            format = preset.name,
                            durationSec = durationSec?.toLong(),
                        ),
                    )
                    // Only what was queued leaves the list: a file added meanwhile (or one that failed) stays.
                    _state.update { it.copy(sources = it.sources - src) }
                }
            } finally {
                _state.update { it.copy(starting = false) }
            }
        }
    }

    companion object {
        fun from(): TranscodeViewModel = TranscodeViewModel(
            detector = AppGraph.encoderDetector,
            // The user's own add: the Queue tab shows the new row (a batch reveals its last one).
            enqueue = { AppGraph.queue.enqueue(it); QueueReveal.request(it.id) },
            materialize = { uri, name -> AppGraph.materializeSource(uri, name) },
            outputDirLabel = { AppGraph.storageSettings.prefs.first().outputDirLabelDefault ?: "" },
            probeDuration = { path -> withContext(Dispatchers.IO) { SourceProbe.probe(path)?.durationSec } },
        )
    }
}
