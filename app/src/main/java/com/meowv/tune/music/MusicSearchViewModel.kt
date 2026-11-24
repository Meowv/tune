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
    val restartRequired: Boolean = false,
    val resultVersion: Int = 0,
    val selectionFailure: MusicFailure? = null,
    val selecting: Boolean = false,
    val selectedId: String? = null,
    val history: List<String> = emptyList()
) {
    val loading: Boolean get() = request != null
}

internal enum class MusicSearchRequest { INITIAL, APPEND, RESTART }

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
        mutable.value = MusicSearchState(visible = true, query = value, history = mutable.value.history)
    }

    fun search() = load(
        if (mutable.value.restartRequired) MusicSearchRequest.RESTART else MusicSearchRequest.INITIAL
    )

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
    fun more() = load(MusicSearchRequest.APPEND)

    private fun load(request: MusicSearchRequest) {
        val old = mutable.value
        if (old.loading || old.selecting || !old.visible) return
        val append = request == MusicSearchRequest.APPEND
        val cursor = if (append) old.nextCursor ?: return else null
        if (append && old.items.size >= 200) return
        val source = repository ?: run {
            mutable.value = old.withLoadFailure(request, MusicFailure.NOT_CONFIGURED)
            return
        }
        val query = old.query.trim()
        if (query.length !in 1..100 || query.any(Char::isISOControl)) {
            mutable.value = old.withLoadFailure(request, MusicFailure.INVALID_REQUEST)
            return
        }
        val id = ++generation
        val keepResults = request != MusicSearchRequest.INITIAL
        mutable.value = old.copy(
            request = request,
            failure = null, appendFailure = null, selectionFailure = null, selectedId = null, searched = true,
            partial = keepResults && old.partial,
            sourceWarnings = if (keepResults) old.sourceWarnings else emptyMap(),
            items = if (keepResults) old.items else emptyList(), nextCursor = cursor
        )
        pending = viewModelScope.launch {
            try {
                val page = source.search(query, 20, cursor)
                ensureActive()
                if (id != generation) return@launch
                val items = mergeResults(if (append) old.items else emptyList(), page.items)
                val warnings = if (append) old.sourceWarnings.filterValues { it != MusicSearchWarning.DEFERRED }.toMutableMap()
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
                    restartRequired = false,
                    resultVersion = if (request == MusicSearchRequest.RESTART) old.resultVersion + 1 else old.resultVersion,
                    nextCursor = page.nextCursor?.takeUnless { it == cursor || items.size >= 200 },
                    sourceWarnings = warnings, partial = page.partial || warnings.isNotEmpty() ||
                        append && old.partial && old.sourceWarnings.isEmpty())
                if (!append && historyStore != null) {
                    historyStore.add(query)
                    mutable.value = mutable.value.copy(history = historyStore.read())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: MusicException) {
                ensureActive()
                if (id == generation) mutable.value = mutable.value.withLoadFailure(request, failure.failure)
            }
        }
    }

    private fun MusicSearchState.withLoadFailure(
        request: MusicSearchRequest,
        reason: MusicFailure
    ): MusicSearchState = when (request) {
        MusicSearchRequest.INITIAL -> copy(request = null, failure = reason)
        MusicSearchRequest.APPEND -> if (reason == MusicFailure.INVALID_REQUEST) {
            copy(request = null, nextCursor = null, restartRequired = true)
        } else {
            copy(request = null, appendFailure = reason)
        }
        MusicSearchRequest.RESTART -> copy(request = null, appendFailure = reason)
    }

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

    fun cancelSelection() {
        if (!mutable.value.selecting) return
        selection?.cancel()
        mutable.value = mutable.value.copy(selecting = false, selectedId = null)
    }

    fun select(track: MusicTrack) {
        if (!mutable.value.visible || mutable.value.selecting ||
            mutable.value.items.none { it.id == track.id && it.canStartPlayback }) return
        mutable.value = mutable.value.copy(selecting = true, selectedId = track.id, selectionFailure = null)
        selection = viewModelScope.launch {
            try {
                play(track)
                ensureActive()
                mutable.value = mutable.value.copy(selecting = false, selectedId = null)
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
