package com.meowv.tune.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meowv.tune.domain.music.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface MusicUiState {
    data object NotConfigured : MusicUiState
    data object Idle : MusicUiState
    data object Loading : MusicUiState
    data class Ready(val track: MusicTrack) : MusicUiState
    data object Empty : MusicUiState
    data class Error(val failure: MusicFailure) : MusicUiState
}

internal class MusicViewModel(
    private val repository: MusicRepository?,
    private val requestPlayback: (suspend (MusicTrack) -> Unit)? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow<MusicUiState>(if (repository == null) MusicUiState.NotConfigured else MusicUiState.Idle)
    val state = mutableState.asStateFlow()
    private var pending: Job? = null
    private var generation = 0L

    fun startListening() {
        val source = repository ?: return
        if (pending?.isActive == true) return
        val current = ++generation
        mutableState.value = MusicUiState.Loading
        pending = viewModelScope.launch {
            try {
                val track = source.recommendations().items.firstOrNull()
                ensureActive()
                if (current != generation) return@launch
                mutableState.value = if (track == null) MusicUiState.Empty else MusicUiState.Ready(track)
                // 播放入口由后续共享协调器注入；没有入口时不伪造正在播放。
                if (track != null && requestPlayback != null) requestPlayback.invoke(track)
            } catch (failure: MusicException) {
                ensureActive()
                if (current == generation) mutableState.value = MusicUiState.Error(failure.failure)
            }
        }
    }

    fun cancel() {
        generation++
        pending?.cancel()
        mutableState.value = if (repository == null) MusicUiState.NotConfigured else MusicUiState.Idle
    }
}
