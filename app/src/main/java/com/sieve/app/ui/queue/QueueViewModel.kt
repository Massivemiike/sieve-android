package com.sieve.app.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sieve.app.di.AppGraph
import com.sieve.queue.core.QueueAggregator
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import com.sieve.queue.core.QueueSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class QueueUiState(val summary: QueueSummary, val jobs: List<QueueJob>) {
    companion object {
        fun from(state: QueueState) =
            QueueUiState(QueueAggregator.summarize(state.jobs), state.jobs.sortedBy { it.position })
    }
}

class QueueViewModel(
    stateSource: StateFlow<QueueState>,
    private val onPause: (String) -> Unit = {},
    private val onResume: (String) -> Unit = {},
    private val onRetry: (String) -> Unit = {},
    private val onCancel: (String) -> Unit = {},
) : ViewModel() {

    val state: StateFlow<QueueUiState> = stateSource
        .map { QueueUiState.from(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUiState.from(stateSource.value))

    fun pause(id: String) = onPause(id)
    fun resume(id: String) = onResume(id)
    fun retry(id: String) = onRetry(id)
    fun cancel(id: String) = onCancel(id)

    companion object {
        fun from(): QueueViewModel {
            val q = AppGraph.queue
            return QueueViewModel(q.state, { q.pause(it) }, { q.resume(it) }, { q.retry(it) }, { q.cancel(it) })
        }
    }
}
