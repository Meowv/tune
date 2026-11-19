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
        var cursor = if (more) previous.cursor else null
        val seenCursors = HashSet<String>().apply { cursor?.let(::add) }
        var tracks = if (more) previous.tracks else emptyList()
        var partial = false
        var loaded = false
        mutableBrowse.value = previous.copy(loading = true, failure = null)
        viewModelScope.launch {
            try {
                for (pageIndex in 0 until MAX_BROWSE_PAGES) {
                    val page = source.recommendations(BROWSE_PAGE_SIZE, cursor)
                    tracks = (tracks + page.items.filter { it.canStartPlayback }).distinctBy { it.id }
                    partial = partial || page.partial
                    loaded = true
                    val next = page.nextCursor?.takeUnless { it == cursor }
                    if (next == null || !seenCursors.add(next)) {
                        cursor = null
                        break
                    }
                    cursor = next
                    mutableBrowse.value = MusicFeed(tracks, cursor, loaded, true, partial)
                }
                mutableBrowse.value = MusicFeed(tracks, cursor, loaded, false, partial)
            } catch (failure: MusicException) {
                mutableBrowse.value = MusicFeed(tracks, cursor, loaded, false, partial, failure.failure)
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

    private companion object {
        const val BROWSE_PAGE_SIZE = 50
        const val MAX_BROWSE_PAGES = 20
    }
}
