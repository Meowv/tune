package com.meowv.tune.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meowv.tune.domain.music.*
import com.meowv.tune.domain.PlaybackSource
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.TuneFailure
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface MusicUiState {
    data object NotConfigured : MusicUiState
    data object Idle : MusicUiState
    data object Loading : MusicUiState
    data class Ready(val track: MusicTrack) : MusicUiState
    data class Active(val playback: PlayerUiState) : MusicUiState
    data object Empty : MusicUiState
    data class Error(val failure: MusicFailure) : MusicUiState
}

internal data class MusicFeed(
    val tracks: List<MusicTrack> = emptyList(),
    val cursor: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val partial: Boolean = false,
    val failure: MusicFailure? = null
)

internal class MusicViewModel(
    private val repository: MusicRepository?,
    private val requestPlayback: (suspend (MusicQueueSeed?) -> Unit)? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    playback: StateFlow<PlayerUiState>? = null
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow<MusicUiState>(if (repository == null) MusicUiState.NotConfigured else MusicUiState.Idle)
    val state = mutableState.asStateFlow()
    private var pending: Job? = null
    private var generation = 0L

    init {
        if (playback != null) viewModelScope.launch {
            playback.collect { value ->
                if (value.source == PlaybackSource.MUSIC && value.phase != PlaybackPhase.IDLE) {
                    mutableState.value = MusicUiState.Active(value)
                } else if (mutableState.value is MusicUiState.Active) {
                    mutableState.value = if (repository == null) MusicUiState.NotConfigured else MusicUiState.Idle
                }
            }
        }
    }

    private val mutableBrowse = MutableStateFlow(MusicFeed())
    val browse = mutableBrowse.asStateFlow()

    fun enter() {
        val feed = mutableBrowse.value
        if (!feed.loaded && feed.failure == null) loadFeed()
    }

    fun loadFeed(more: Boolean = false) {
        val source = repository ?: return
        val previous = mutableBrowse.value
        if (previous.loading || more && previous.cursor == null) return
        val cursor = if (more) previous.cursor else null
        mutableBrowse.value = previous.copy(loading = true, failure = null)
        viewModelScope.launch {
            try {
                val page = source.recommendations(cursor = cursor)
                val tracks = (if (more) previous.tracks else emptyList()) + page.items.filter { it.canStartPlayback }
                mutableBrowse.value = MusicFeed(tracks = tracks.distinctBy { it.id }, loaded = true,
                    cursor = page.nextCursor?.takeUnless { it == cursor }, partial = page.partial)
            } catch (failure: MusicException) {
                mutableBrowse.value = previous.copy(loading = false, failure = failure.failure)
            }
        }
    }

    fun startListening() {
        val source = repository ?: return
        if (pending?.isActive == true) return
        val current = ++generation
        mutableState.value = MusicUiState.Loading
        pending = viewModelScope.launch {
            try {
                if (requestPlayback != null) {
                    val feed = mutableBrowse.value
                    requestPlayback(feed.tracks.takeIf { it.isNotEmpty() }?.let { MusicQueueSeed(it, feed.cursor) })
                    return@launch
                }
                val track = source.recommendations().items.firstOrNull { it.canStartPlayback }
                ensureActive()
                if (current != generation) return@launch
                mutableState.value = if (track == null) MusicUiState.Empty else MusicUiState.Ready(track)
            } catch (_: TuneFailure) {
                ensureActive()
                if (current == generation) mutableState.value = MusicUiState.Error(MusicFailure.PLAYBACK_UNAVAILABLE)
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
