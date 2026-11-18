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

internal enum class MusicSection { Recommendations, Chart }

internal data class MusicFeed(
    val rows: List<MusicChartRow> = emptyList(),
    val cursor: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val partial: Boolean = false,
    val stale: Boolean = false,
    val failure: MusicFailure? = null
)

internal data class MusicBrowseState(
    val section: MusicSection = MusicSection.Recommendations,
    val feeds: Map<MusicSection, MusicFeed> = emptyMap()
) {
    val feed: MusicFeed get() = feeds[section] ?: MusicFeed()
}

internal class MusicViewModel(
    private val repository: MusicRepository?,
    private val requestPlayback: (suspend () -> Unit)? = null,
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

    private val mutableBrowse = MutableStateFlow(MusicBrowseState())
    val browse = mutableBrowse.asStateFlow()

    fun enter() = selectSection(mutableBrowse.value.section)

    fun selectSection(section: MusicSection) {
        mutableBrowse.value = mutableBrowse.value.copy(section = section)
        val feed = mutableBrowse.value.feed
        if (!feed.loaded && feed.failure == null) loadFeed()
    }

    fun loadFeed(more: Boolean = false) {
        val source = repository ?: return
        val (section) = mutableBrowse.value
        val previous = mutableBrowse.value.feed
        if (previous.loading || more && previous.cursor == null) return
        val cursor = if (more) previous.cursor else null
        updateFeed(section, previous.copy(loading = true, failure = null))
        viewModelScope.launch {
            try {
                val feed = when (section) {
                    MusicSection.Chart -> source.charts().let {
                        MusicFeed(rows = it.rows.distinctBy { row -> row.track.id }, loaded = true,
                            partial = it.partial, stale = it.stale)
                    }
                    MusicSection.Recommendations -> source.recommendations(cursor = cursor).let {
                        val rows = (if (more) previous.rows else emptyList()) + it.items.map { track -> MusicChartRow(track) }
                        MusicFeed(rows = rows.distinctBy { row -> row.track.id }, loaded = true,
                            cursor = it.nextCursor?.takeUnless { next -> next == cursor }, partial = it.partial)
                    }
                }
                updateFeed(section, feed)
            } catch (failure: MusicException) {
                updateFeed(section, previous.copy(loading = false, failure = failure.failure))
            }
        }
    }

    private fun updateFeed(section: MusicSection, feed: MusicFeed) {
        val state = mutableBrowse.value
        mutableBrowse.value = state.copy(feeds = state.feeds + (section to feed))
    }

    fun startListening() {
        val source = repository ?: return
        if (pending?.isActive == true) return
        val current = ++generation
        mutableState.value = MusicUiState.Loading
        pending = viewModelScope.launch {
            try {
                if (requestPlayback != null) {
                    requestPlayback.invoke()
                    return@launch
                }
                val track = source.recommendations().items.firstOrNull()
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
