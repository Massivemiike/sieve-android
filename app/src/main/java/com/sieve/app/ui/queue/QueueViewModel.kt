package com.sieve.app.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sieve.app.di.AppGraph
import com.sieve.queue.core.QueueAggregator
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.QueueState
import com.sieve.queue.core.QueueSummary
import com.sieve.queue.core.RestoreHold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class QueueUiState(
    val summary: QueueSummary,
    val jobs: List<QueueJob>,
    /** N in "Restored N unfinished items": the restored rows still waiting for the user. 0 = no banner. */
    val restoredHeld: Int = 0,
) {
    /** Completed + failed + cancelled rows: what "Clear finished" removes. */
    val finished: Int get() = jobs.count { it.status.isTerminal }

    val showRestoreBanner: Boolean get() = restoredHeld > 0

    companion object {
        /** [restore] decides the banner: it counts the held rows that are still paused, and is off once dismissed. */
        fun from(state: QueueState, restore: RestoreHold = RestoreHold()) = QueueUiState(
            QueueAggregator.summarize(state.jobs), state.jobs.sortedBy { it.position },
            restoredHeld = if (restore.bannerDismissed) 0 else restore.stillHeldIn(state).heldIds.size,
        )
    }
}

class QueueViewModel(
    stateSource: StateFlow<QueueState>,
    private val onPause: (String) -> Unit = {},
    private val onResume: (String) -> Unit = {},
    private val onRetry: (String) -> Unit = {},
    private val onCancel: (String) -> Unit = {},
    private val onRemove: (String) -> Unit = {},
    private val onClearFinished: () -> Unit = {},
    restoreSource: StateFlow<RestoreHold> = MutableStateFlow(RestoreHold()),
    private val onResumeHeld: () -> Unit = {},
    private val onDismissRestore: () -> Unit = {},
) : ViewModel() {

    val state: StateFlow<QueueUiState> = combine(stateSource, restoreSource) { s, r -> QueueUiState.from(s, r) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUiState.from(stateSource.value, restoreSource.value))

    fun pause(id: String) = onPause(id)
    fun resume(id: String) = onResume(id)
    fun retry(id: String) = onRetry(id)
    fun cancel(id: String) = onCancel(id)
    fun remove(id: String) = onRemove(id)
    fun clearFinished() = onClearFinished()

    /** The banner's "Resume all". */
    fun resumeAllHeld() = onResumeHeld()
    /** The banner's "Dismiss": hides it for good; the rows stay paused. */
    fun dismissRestore() = onDismissRestore()

    companion object {
        fun from(): QueueViewModel {
            val q = AppGraph.queue
            return QueueViewModel(
                q.state, { q.pause(it) }, { q.resume(it) }, { q.retry(it) }, { q.cancel(it) },
                { q.remove(it) }, { q.clearFinished() },
                q.restore, { q.resumeHeld() }, { q.dismissRestoreBanner() },
            )
        }
    }
}
