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
import kotlinx.coroutines.flow.update

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
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val failure: MusicFailure? = null,
    val seed: MusicQueueSeed? = null
)

internal class MusicViewModel(
    private val repository: MusicRepository?,
    private val requestPlayback: (suspend (MusicQueueSeed?) -> Unit)? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    playback: StateFlow<PlayerUiState>? = null,
    private val feedStore: MusicFeedStore = MusicFeedStore(),
    private val listening: MusicListeningHistory? = null
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

    init {
        if (repository != null) viewModelScope.launch {
            feedStore.snapshot.collect { snapshot ->
                val tracks = ranked(snapshot)
                mutableBrowse.update { it.copy(tracks = tracks, seed = snapshot.seed(tracks)) }
            }
        }
    }

    fun enter() {
        val feed = mutableBrowse.value
        if (!feed.loaded && feed.failure == null) loadFeed()
    }

    fun onForeground() {
        val feed = mutableBrowse.value
        if (feed.loaded || feed.failure != null) loadFeed()
    }

    fun loadFeed() {
        val source = repository ?: return
        val previous = mutableBrowse.value
        if (previous.loading) return
        mutableBrowse.value = previous.copy(loading = true, failure = null)
        viewModelScope.launch {
            feedStore.restore()
            try {
                val page = source.firstRecommendations()
                val snapshot = feedStore.updateRecommendations(page)
                val tracks = ranked(snapshot)
                val failure = if (tracks.isEmpty() && page.partial) {
                    when {
                        page.warnings.any { it.endsWith(":rate_limited") } -> MusicFailure.RATE_LIMITED
                        page.warnings.any { it.endsWith(":source_timeout") } -> MusicFailure.SOURCE_TIMEOUT
                        else -> MusicFailure.SOURCE_UNAVAILABLE
                    }
                } else null
                mutableBrowse.value = MusicFeed(tracks, loaded = true, failure = failure, seed = snapshot.seed(tracks))
                listening?.displayed(tracks)
            } catch (failure: MusicException) {
                mutableBrowse.update { it.copy(loading = false, failure = failure.failure) }
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
                    val feed = feedStore.restore()
                    val tracks = ranked(feed)
                    requestPlayback(feed.seed(tracks))
                    return@launch
                }
                val track = source.firstRecommendations().items.firstOrNull { it.canStartPlayback }
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

    private fun MusicFeedSnapshot.seed(tracks: List<MusicTrack>) =
        tracks.takeIf { it.isNotEmpty() }?.let { MusicQueueSeed(it, nextCursor, rolling, version) }

    private suspend fun ranked(feed: MusicFeedSnapshot) =
        listening?.rank(feed.tracks, feed.discoveryTrackIds) ?: feed.tracks

    private suspend fun MusicRepository.firstRecommendations(): MusicPage {
        if (listening == null) return recommendations()
        val first = try {
            candidates()
        } catch (failure: MusicException) {
            if (failure.failure !in setOf(MusicFailure.TRACK_NOT_FOUND, MusicFailure.SOURCE_UNAVAILABLE,
                    MusicFailure.SOURCE_TIMEOUT, MusicFailure.INVALID_RESPONSE)) throw failure
            return recommendations()
        }
        if (first.strategy != "rolling_candidates") {
            return if (first.strategy == "rolling_warming") recommendations() else first
        }

        // 权益过滤可能留下空页，保留其他已读候选，避免首次刷新阻塞太久。
        var page = first
        withTimeoutOrNull(2_000) {
            repeat(2) {
                val cursor = page.nextCursor ?: return@withTimeoutOrNull
                if (listening.freshCount(page.items) >= 20) return@withTimeoutOrNull
                val next = try { candidates(cursor = cursor) } catch (_: MusicException) { return@withTimeoutOrNull }
                page = page.copy(items = (page.items + next.items).distinctBy { it.id },
                    nextCursor = next.nextCursor?.takeUnless { it == cursor },
                    discoveryTrackIds = page.discoveryTrackIds + next.discoveryTrackIds)
            }
        }
        return if (page.items.isEmpty() && page.nextCursor == null) recommendations() else page
    }

    fun cancel() {
        generation++
        pending?.cancel()
        mutableState.value = if (repository == null) MusicUiState.NotConfigured else MusicUiState.Idle
    }

}
