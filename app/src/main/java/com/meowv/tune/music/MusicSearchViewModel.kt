package com.meowv.tune.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meowv.tune.domain.TuneFailure
import com.meowv.tune.domain.music.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class MusicSearchState(
    val visible: Boolean = false,
    val query: String = "",
    val searched: Boolean = false,
    val items: List<MusicTrack> = emptyList(),
    val nextCursor: String? = null,
    val request: MusicSearchRequest? = null,
    val partial: Boolean = false,
    val sourceWarnings: Map<String, MusicSearchWarning> = emptyMap(),
    val failure: MusicFailure? = null,
    val appendFailure: MusicFailure? = null,
    val selectionFailure: MusicFailure? = null,
    val selecting: Boolean = false,
    val selectedId: String? = null,
    val history: List<String> = emptyList()
) {
    val loading: Boolean get() = request != null
}

internal enum class MusicSearchRequest { INITIAL, APPEND }

internal enum class MusicSearchWarning { DEFERRED, TIMEOUT, RATE_LIMITED, UNAVAILABLE }

internal class MusicSearchViewModel(
    private val repository: MusicRepository?,
    private val play: suspend (MusicTrack) -> Unit,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val historyStore: MusicSearchHistoryStore? = null
) : ViewModel(scope) {
    private val mutable = MutableStateFlow(MusicSearchState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var pending: Job? = null
    private var selection: Job? = null

    init {
        viewModelScope.launch {
            historyStore?.read()?.let { values -> mutable.value = mutable.value.copy(history = values) }
        }
    }

    fun open() { mutable.value = mutable.value.copy(visible = true) }

    fun close() {
        generation++
        pending?.cancel(); selection?.cancel()
        val old = mutable.value
        mutable.value = old.copy(visible = false, request = null, selecting = false,
            selectedId = null, selectionFailure = null,
            searched = old.searched && old.request != MusicSearchRequest.INITIAL)
    }

    fun queryChanged(value: String) {
        if (value == mutable.value.query || mutable.value.selecting) return
        generation++
        pending?.cancel()
        mutable.value = MusicSearchState(visible = true, query = value)
    }

    fun search() {
        val query = mutable.value.query.trim()
        if (query.isNotEmpty()) {
            viewModelScope.launch {
                historyStore?.add(query)
                mutable.value = mutable.value.copy(history = historyStore?.read().orEmpty())
            }
        }
        load(more = false)
    }

    fun selectHistory(query: String) {
        queryChanged(query)
        search()
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyStore?.clear()
            mutable.value = mutable.value.copy(history = emptyList())
        }
    }
    fun more() = load(more = true)

    private fun load(more: Boolean) {
        val old = mutable.value
        if (old.loading || old.selecting || !old.visible) return
        val cursor = if (more) old.nextCursor ?: return else null
        if (more && old.items.size >= 200) return
        val source = repository ?: run {
            mutable.value = old.withLoadFailure(more, MusicFailure.NOT_CONFIGURED)
            return
        }
        val query = old.query.trim()
        if (query.length !in 1..100 || query.any(Char::isISOControl)) {
            mutable.value = old.withLoadFailure(more, MusicFailure.INVALID_REQUEST)
            return
        }
        val id = ++generation
        mutable.value = old.copy(
            request = if (more) MusicSearchRequest.APPEND else MusicSearchRequest.INITIAL,
            failure = null, appendFailure = null, selectionFailure = null, selectedId = null, searched = true,
            partial = more && old.partial, sourceWarnings = if (more) old.sourceWarnings else emptyMap(),
            items = if (more) old.items else emptyList(), nextCursor = cursor
        )
        pending = viewModelScope.launch {
            try {
                val page = source.search(query, 20, cursor)
                ensureActive()
                if (id != generation) return@launch
                val items = mergeResults(if (more) old.items else emptyList(), page.items)
                val warnings = if (more) old.sourceWarnings.filterValues { it != MusicSearchWarning.DEFERRED }.toMutableMap()
                    else mutableMapOf()
                for (warning in page.warnings) {
                    val provider = warning.substringBefore(':')
                    if (provider !in setOf("netease", "qqmusic", "search")) continue
                    warnings[provider] = when (warning.substringAfter(':')) {
                        "deferred" -> MusicSearchWarning.DEFERRED
                        "source_timeout" -> MusicSearchWarning.TIMEOUT
                        "rate_limited" -> MusicSearchWarning.RATE_LIMITED
                        "source_unavailable", "forbidden", "unauthorized" -> MusicSearchWarning.UNAVAILABLE
                        else -> continue
                    }
                }
                mutable.value = mutable.value.copy(items = items, request = null,
                    nextCursor = page.nextCursor?.takeUnless { it == cursor || items.size >= 200 },
                    sourceWarnings = warnings, partial = page.partial || warnings.isNotEmpty() ||
                        more && old.partial && old.sourceWarnings.isEmpty())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: MusicException) {
                ensureActive()
                if (id == generation) mutable.value = mutable.value.withLoadFailure(more, failure.failure)
            }
        }
    }

    private fun MusicSearchState.withLoadFailure(more: Boolean, reason: MusicFailure): MusicSearchState =
        if (more) copy(request = null, appendFailure = reason)
        else copy(request = null, failure = reason)

    private fun mergeResults(previous: List<MusicTrack>, incoming: List<MusicTrack>): List<MusicTrack> {
        val merged = previous.toMutableList()
        for (track in incoming) {
            val at = merged.indexOfFirst { old -> old.id == track.id || old.matches.any { left ->
                track.matches.any { right -> left.providerId == right.providerId && left.sourceTrackId == right.sourceTrackId }
            } }
            if (at < 0) {
                if (merged.size < 200) merged += track
            } else if (merged[at].id == track.id || track.canStartPlayback || !merged[at].canStartPlayback) merged[at] = track
        }
        return merged
    }

    fun select(track: MusicTrack) {
        if (!mutable.value.visible || mutable.value.selecting ||
            mutable.value.items.none { it.id == track.id && it.canStartPlayback }) return
        mutable.value = mutable.value.copy(selecting = true, selectedId = track.id, selectionFailure = null)
        selection = viewModelScope.launch {
            try {
                play(track)
                ensureActive()
                generation++
                pending?.cancel()
                mutable.value = mutable.value.copy(visible = false, request = null, selecting = false, selectedId = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: TuneFailure) {
                ensureActive()
                mutable.value = mutable.value.copy(selecting = false, selectionFailure = MusicFailure.PLAYBACK_UNAVAILABLE)
            } catch (failure: MusicException) {
                ensureActive()
                mutable.value = mutable.value.copy(selecting = false, selectionFailure = failure.failure)
            }
        }
    }
}
