package com.meowv.tune.music

import com.meowv.tune.domain.music.*
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

internal data class MusicSelection(val token: Long, val track: MusicTrack, internal val historyIndex: Int? = null)

internal class MusicQueueManager(
    private val repository: () -> MusicRepository,
    private val batchSize: Int = 20,
    private val lowWater: Int = 8,
    private val historyLimit: Int = 100,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val epochSeconds: () -> Long = { Instant.now().epochSecond }
) {
    private val pending = ArrayDeque<MusicTrack>()
    private val history = mutableListOf<MusicTrack>()
    private val considered = LinkedHashSet<String>()
    private val played = LinkedHashSet<String>()
    private val skipped = LinkedHashSet<String>()
    private val skipArtists = ArrayDeque<Set<String>>()
    private val seenCursors = LinkedHashSet<String?>()
    private var lastSkipAt: Long? = null
    private var lastSkippedId: String? = null
    private val load = Mutex()
    private var randomNext: String? = null
    var shuffle: Boolean = false
        set(value) {
            if (field != value) randomNext = null
            field = value
        }
    private var cursor: String? = null
    private var exhausted = false
    private data class SearchPosition(
        val hour: Long, val query: String, val cursor: String? = null,
        val done: Boolean = false, val seen: Set<String> = emptySet()
    )
    private var searchPosition: SearchPosition? = null
    private val radioBuffer = ArrayDeque<MusicTrack>()
    private val searchBuffer = ArrayDeque<MusicTrack>()
    private var lastRadioLoad: Long? = null
    private var fallbackFailure: MusicException? = null
    private var emptyCycles = 0
    private var issued = 0L
    private var committed = -1L
    private var index = -1
    private var revision = 0L

    init { require(batchSize in 1..50 && lowWater >= 1 && historyLimit >= 2) }
    val canPrevious: Boolean get() = index > 0
    val canNext: Boolean get() = index < history.lastIndex || pending.isNotEmpty() ||
        !exhausted || fallbackFailure == null && emptyCycles < MAX_EMPTY_CYCLES
    val nextTrack: MusicTrack? get() = if (index < history.lastIndex) history[index + 1] else nextCandidate()
    private val remaining: Int get() = pending.size + (history.lastIndex - index).coerceAtLeast(0)
    val needsReplenish: Boolean get() = remaining < lowWater && !exhausted
    val needsFallbackPrefetch: Boolean get() = exhausted && remaining in 1..2 &&
        fallbackFailure == null && emptyCycles < MAX_EMPTY_CYCLES
    val sessionVersion: Long get() = revision

    fun invalidate() { issued++ }
    fun renew(selection: MusicSelection) = selection.copy(token = ++issued)

    fun skip(track: MusicTrack, affectRanking: Boolean = true) {
        remember(skipped, track.id)
        pending.removeAll { it.id == track.id }
        if (!affectRanking) return
        val now = nowMillis()
        if (lastSkipAt?.let { now - it in 0..30000 } != true) clearSkipSignal()
        if (lastSkippedId == track.id) return
        lastSkipAt = now
        lastSkippedId = track.id
        skipArtists.addLast(artists(track))
        while (skipArtists.size > 3) skipArtists.removeFirst()
    }

    fun clearSkipSignal() { lastSkipAt = null; lastSkippedId = null; skipArtists.clear() }

    fun select(track: MusicTrack): MusicSelection {
        pending.removeAll { it.id == track.id }
        remember(track.id)
        return MusicSelection(++issued, track)
    }

    suspend fun next(): MusicSelection? {
        if (index < history.lastIndex) return MusicSelection(++issued, history[index + 1], index + 1)
        val owner = revision
        if (pending.isEmpty() && !exhausted) replenish(minimumOnly = true)
        if (pending.isEmpty() && exhausted && owner == revision) load.withLock {
            if (owner != revision) return@withLock
            fallbackFailure?.let { throw it }
            repeat(MAX_EMPTY_CYCLES) {
                if (owner != revision || pending.isNotEmpty() || emptyCycles >= MAX_EMPTY_CYCLES) return@withLock
                refillRound()
            }
        }
        currentCoroutineContext().ensureActive()
        if (owner != revision) return null
        val track = nextCandidate() ?: return null
        pending.remove(track)
        randomNext = null
        return MusicSelection(++issued, track)
    }

    fun previous(): MusicSelection? = if (canPrevious) MusicSelection(++issued, history[index - 1], index - 1) else null

    suspend fun replenish(minimumOnly: Boolean = false) = load.withLock {
        val owner = revision
        var added = 0
        // 单次最多读取两页；重复游标或全重复目录不能造成无界补队列。
        repeat(2) {
            if (!needsReplenish || minimumOnly && pending.isNotEmpty()) return@withLock
            val requestedCursor = cursor
            val page = repository().recommendations(batchSize, requestedCursor)
            currentCoroutineContext().ensureActive()
            if (owner != revision) return@withLock
            if (page.nextCursor != null && page.items.size > batchSize) throw MusicException(MusicFailure.INVALID_RESPONSE)
            val known = considered + played + skipped + pending.map { it.id } + history.map { it.id }
            page.items.distinctBy { it.id }.filter { it.canStartPlayback }.filterNot { it.id in known }.forEach {
                pending.addLast(it)
                remember(it.id)
                added++
            }
            seenCursors.add(requestedCursor)
            while (seenCursors.size > 100) seenCursors.remove(seenCursors.first())
            cursor = page.nextCursor
            exhausted = cursor == null || cursor in seenCursors
        }
        if (added == 0) { exhausted = true; cursor = null }
    }

    suspend fun prefetchFallback() = load.withLock {
        if (needsFallbackPrefetch) refillRound(countEmpty = false)
    }

    private suspend fun refillRound(countEmpty: Boolean = true) {
        val owner = revision
        val source = repository()
        val context = history.getOrNull(index)
        var hour = Math.floorDiv(epochSeconds(), 3600L)
        var query = source.fallbackSearchQueries(context).firstOrNull()?.trim().orEmpty()
        val currentHour = Math.floorDiv(epochSeconds(), 3600L)
        if (hour != currentHour) {
            hour = currentHour
            query = source.fallbackSearchQueries(context).firstOrNull()?.trim().orEmpty()
        }
        if (searchPosition?.let { it.hour == hour && it.query == query } != true) {
            searchBuffer.clear()
            searchPosition = SearchPosition(hour, query)
        }
        var position = checkNotNull(searchPosition)
        val known = considered + played + skipped + pending.map { it.id } + history.map { it.id }
        fun fresh(track: MusicTrack) = track.canStartPlayback && track.id !in known

        val radio = mutableListOf<MusicTrack>()
        val search = mutableListOf<MusicTrack>()
        var radioFetched = false
        var radioError: MusicException? = null
        var searchError: MusicException? = null
        val fetchRadio = radioBuffer.none(::fresh) &&
            lastRadioLoad?.let { nowMillis() - it < RADIO_REUSE_MS } != true
        val buffered = searchBuffer.count(::fresh) + radioBuffer.count(::fresh)
        val fetchSearch = !position.done && buffered < FALLBACK_BATCH &&
            query.length in 1..100 && query.none(Char::isISOControl)
        var timedOut = false
        try {
            coroutineScope {
                val radioRequest = async {
                    if (fetchRadio) try {
                        radio += source.radioRecommendations()
                        radioFetched = true
                    } catch (failure: MusicException) {
                        if (failure.failure in STOP_FALLBACK) throw failure
                        radioError = failure
                    }
                }
                val searchRequest = async {
                    if (fetchSearch) try {
                        repeat(SEARCH_PAGES_PER_ROUND) {
                            if (position.done || search.asSequence().filter(::fresh).distinctBy { it.id }
                                    .take(FALLBACK_BATCH).count() + buffered >= FALLBACK_BATCH) return@async
                            val page = source.search(query, SEARCH_PAGE_SIZE, position.cursor)
                            currentCoroutineContext().ensureActive()
                            search += page.items
                            val cursor = page.nextCursor?.takeUnless { it == position.cursor || it in position.seen }
                            position = position.copy(cursor = cursor, done = cursor == null,
                                seen = position.seen + listOfNotNull(cursor))
                            if (cursor == null && page.partial && search.none(::fresh)) {
                                searchError = MusicException(MusicFailure.SOURCE_UNAVAILABLE)
                                position = position.copy(done = false)
                                return@async
                            }
                        }
                    } catch (failure: MusicException) {
                        if (failure.failure in STOP_FALLBACK) throw failure
                        if (failure.failure == MusicFailure.INVALID_REQUEST && position.cursor != null) {
                            // 服务重启或游标过期后，下一轮从同一关键词的第一页继续。
                            position = SearchPosition(hour, query)
                        } else {
                            searchError = failure
                        }
                    }
                }
                timedOut = withTimeoutOrNull(FALLBACK_ROUND_MS) {
                    radioRequest.await()
                    if (radio.any(::fresh) || radioBuffer.any(::fresh)) {
                        withTimeoutOrNull(SEARCH_GRACE_MS) { searchRequest.await() }
                    } else searchRequest.await()
                    false
                } ?: true
                searchRequest.cancelAndJoin()
                radioRequest.cancelAndJoin()
            }
        } catch (failure: MusicException) {
            if (owner == revision && failure.failure in STOP_FALLBACK) fallbackFailure = failure
            throw failure
        }

        currentCoroutineContext().ensureActive()
        if (owner != revision) return
        if (radioFetched) lastRadioLoad = nowMillis()
        searchPosition = position
        radioBuffer.addAll(radio.filter(::fresh))
        searchBuffer.addAll(search.filter(::fresh))

        var added = 0
        for (buffer in listOf(radioBuffer, searchBuffer)) {
            while (added < FALLBACK_BATCH && buffer.isNotEmpty()) {
                val track = buffer.removeFirst()
                if (!fresh(track) || track.id in considered) continue
                pending.addLast(track)
                remember(track.id)
                added++
            }
        }
        if (added > 0) {
            emptyCycles = 0
            return
        }
        if (timedOut || radioError != null || searchError != null) {
            throw radioError ?: searchError ?: MusicException(MusicFailure.SOURCE_TIMEOUT)
        }
        if (countEmpty) emptyCycles++
    }

    fun recordSuccessfulPlayback(selection: MusicSelection): Boolean {
        if (selection.token != issued || committed == selection.token) return false
        committed = selection.token
        remember(played, selection.track.id)
        if (selection.historyIndex != null) {
            index = selection.historyIndex
        } else {
            while (history.lastIndex > index) history.removeAt(history.lastIndex)
            if (history.lastOrNull()?.id != selection.track.id) history.add(selection.track)
            while (history.size > historyLimit) history.removeAt(0)
            index = history.lastIndex
        }
        return true
    }

    fun reset(seed: MusicQueueSeed? = null, startAt: String? = null) {
        revision++
        invalidate()
        pending.clear(); history.clear(); considered.clear(); played.clear(); skipped.clear(); seenCursors.clear()
        clearSkipSignal()
        cursor = null; exhausted = false; emptyCycles = 0
        searchPosition = null; lastRadioLoad = null; fallbackFailure = null
        radioBuffer.clear(); searchBuffer.clear()
        index = -1; randomNext = null
        if (seed != null) {
            val tracks = seed.tracks.filter { it.canStartPlayback }.distinctBy { it.id }
            val selected = tracks.indexOfFirst { it.id == startAt }
            pending.addAll(if (shuffle) tracks.filterNot { it.id == startAt } else tracks.drop(selected + 1))
            tracks.forEach { remember(it.id) }
            cursor = seed.nextCursor
            exhausted = cursor == null
        }
    }

    private fun remember(id: String) {
        considered.add(id)
    }

    private fun remember(window: LinkedHashSet<String>, id: String) {
        window.remove(id); window.add(id)
        while (window.size > historyLimit) window.remove(window.first())
    }

    private fun nextCandidate(): MusicTrack? {
        val shortWindow = lastSkipAt?.let { nowMillis() - it in 0..30000 } == true
        if (!shortWindow || skipArtists.size < 2) return chooseNext(pending)
        val avoided = skipArtists.flatten().toSet()
        // 只在已有候选内调整顺序；没有其他歌手时保留原顺序，不额外追页。
        val candidates = pending.filter { track ->
            val names = artists(track)
            names.isNotEmpty() && names.none { it in avoided }
        }
        return chooseNext(candidates.ifEmpty { pending })
    }

    private fun chooseNext(tracks: List<MusicTrack>): MusicTrack? {
        if (!shuffle) return tracks.firstOrNull()
        return tracks.firstOrNull { it.id == randomNext }
            ?: tracks.randomOrNull()?.also { randomNext = it.id }
    }

    private fun artists(track: MusicTrack): Set<String> = track.artists.map {
        Normalizer.normalize(it.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    }.filter { it.isNotEmpty() }.toSet()

    private companion object {
        const val MAX_EMPTY_CYCLES = 2
        const val SEARCH_PAGES_PER_ROUND = 2
        const val SEARCH_PAGE_SIZE = 20
        const val FALLBACK_BATCH = 12
        const val FALLBACK_ROUND_MS = 2_500L
        const val SEARCH_GRACE_MS = 250L
        const val RADIO_REUSE_MS = 15_000L
        val STOP_FALLBACK = setOf(MusicFailure.RATE_LIMITED, MusicFailure.CONNECTION, MusicFailure.TLS,
            MusicFailure.UNAUTHORIZED, MusicFailure.FORBIDDEN, MusicFailure.NOT_CONFIGURED)
    }
}
