package com.sieve.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sieve.app.di.AppGraph
import com.sieve.app.ui.common.SharedTranscodeSource
import com.sieve.storage.library.DocumentStore
import com.sieve.storage.library.LibraryEntry
import com.sieve.storage.library.LibraryFilter
import com.sieve.storage.library.LibraryNavigator
import com.sieve.storage.library.MediaKind
import com.sieve.storage.library.SortKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class LibraryUiState(
    val treeUri: String? = null,
    val entries: List<LibraryEntry> = emptyList(),
    val filter: MediaKind = MediaKind.ALL,
    val query: String = "",
    val loading: Boolean = false,
    val canGoUp: Boolean = false,
    val folderName: String = "Sieve",
)

class LibraryViewModel(
    private val store: DocumentStore,
    treeUriFlow: Flow<String?>,
    private val folderLabelFlow: Flow<String?>,
    private val persistTree: suspend (String) -> Unit,
) : ViewModel() {

    private var navigator: LibraryNavigator? = null
    private var raw: List<LibraryEntry> = emptyList()
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { treeUriFlow.collect { onTree(it) } }
        viewModelScope.launch { folderLabelFlow.map { it ?: "Sieve" }.collect { _state.value = _state.value.copy(folderName = it) } }
    }

    private suspend fun onTree(uri: String?) {
        if (uri.isNullOrBlank()) {
            navigator = null; raw = emptyList()
            _state.value = _state.value.copy(treeUri = null, entries = emptyList(), canGoUp = false)
            return
        }
        navigator = LibraryNavigator(rootDocumentId = "")
        _state.value = _state.value.copy(treeUri = uri)
        list()
    }

    private fun currentParentId(): String? = navigator?.current?.takeIf { it.isNotEmpty() }

    private suspend fun list() {
        val uri = _state.value.treeUri ?: return
        _state.value = _state.value.copy(loading = true)
        raw = runCatching { store.listChildren(uri, currentParentId()) }.getOrDefault(emptyList())
        _state.value = _state.value.copy(
            entries = filtered(),
            loading = false,
            canGoUp = navigator?.canGoUp() == true,
        )
    }

    private fun filtered() =
        LibraryFilter.apply(raw, _state.value.filter, _state.value.query, SortKey.MODIFIED, ascending = false)

    fun onGrantTree(uri: String) { viewModelScope.launch { persistTree(uri) } }

    fun enter(entry: LibraryEntry) {
        if (!entry.isDir) return
        navigator?.enter(entry.documentId)
        viewModelScope.launch { list() }
    }

    fun up() {
        navigator?.up()
        viewModelScope.launch { list() }
    }

    fun setFilter(kind: MediaKind) {
        _state.value = _state.value.copy(filter = kind, entries = LibraryFilter.apply(raw, kind, _state.value.query, SortKey.MODIFIED, false))
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q, entries = LibraryFilter.apply(raw, _state.value.filter, q, SortKey.MODIFIED, false))
    }

    fun delete(entry: LibraryEntry) {
        viewModelScope.launch {
            runCatching { store.delete(entry.uri) }
            raw = raw.filterNot { it.uri == entry.uri }
            _state.value = _state.value.copy(entries = filtered())
        }
    }

    /** Publishes the file to the Transcode screen; the caller navigates to that tab. */
    fun sendToTranscode(entry: LibraryEntry) {
        SharedTranscodeSource.publish(entry.uri, entry.name)
    }

    companion object {
        fun from(): LibraryViewModel {
            val s = AppGraph.storageSettings
            return LibraryViewModel(
                store = AppGraph.documentStore,
                treeUriFlow = s.prefs.map { it.outputTreeUri },
                folderLabelFlow = s.prefs.map { it.outputDirLabelDefault },
                persistTree = { s.setOutputTree(it) },
            )
        }
    }
}
