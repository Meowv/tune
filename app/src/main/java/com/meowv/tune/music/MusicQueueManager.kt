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
    private val epochSeconds: () -> Long = { Instant.now().epochSecond },
    private val feedStore: MusicFeedStore? = null,
    private val listening: MusicListeningHistory? = null
) {
    private val pending = ArrayDeque<MusicTrack>()
    private val history = mutableListOf<MusicTrack>()
    private val considered = LinkedHashSet<String>()
    private val recordings = mutableListOf<MusicRecording>()
    private var rolling = false
    private var rollingFeedVersion: Long? = null
    private var retriedCursor = false
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
    private var fallbackFeedVersion: Long? = null
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
    val needsReplenish: Boolean get() = (if (rolling) remaining <= 2 else remaining < lowWater) && !exhausted
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
        remember(track)
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
        // 前台仅在没有待播曲目时继续跨过空页，预取仍以两页为限。
        repeat(if (rolling && minimumOnly) 6 else 2) {
            if (!needsReplenish || minimumOnly && pending.isNotEmpty()) return@withLock
            val requestedCursor = cursor
            val source = repository()
            val page = try {
                if (rolling) source.candidates(cursor = requestedCursor) else source.recommendations(batchSize, requestedCursor)
            } catch (failure: MusicException) {
                if (rolling && requestedCursor != null && failure.failure == MusicFailure.INVALID_REQUEST && !retriedCursor) {
                    retriedCursor = true
                    cursor = null
                    return@repeat
                }
                if (!rolling || failure.failure !in setOf(MusicFailure.INVALID_REQUEST, MusicFailure.TRACK_NOT_FOUND,
                        MusicFailure.SOURCE_UNAVAILABLE, MusicFailure.SOURCE_TIMEOUT, MusicFailure.INVALID_RESPONSE)) throw failure
                exhausted = true
                cursor = null
                return@withLock
            }
            currentCoroutineContext().ensureActive()
            if (owner != revision) return@withLock
            val pageLimit = if (rolling) 30 else batchSize
            if (page.nextCursor != null && page.items.size > pageLimit) throw MusicException(MusicFailure.INVALID_RESPONSE)
            if (rolling && page.strategy == "rolling_warming") {
                exhausted = true
                cursor = null
                return@withLock
            }
            val known = considered + played + skipped + pending.map { it.id } + history.map { it.id }
            val eligible = page.items.distinctBy { it.id }.filter { it.canStartPlayback && it.id !in known }
            val ordered = listening?.rank(eligible, page.discoveryTrackIds, pending.lastOrNull() ?: history.getOrNull(index)) ?: eligible
            if (owner != revision) return@withLock
            val accepted = ordered.filterNot(::seenRecording)
            accepted.forEach {
                pending.addLast(it)
                remember(it)
                added++
            }
            if (rolling && accepted.isNotEmpty())
                rollingFeedVersion?.let { feedStore?.appendFallback(it, accepted, page.discoveryTrackIds) }
            seenCursors.add(requestedCursor)
            while (seenCursors.size > 100) seenCursors.remove(seenCursors.first())
            cursor = page.nextCursor
            exhausted = cursor == null || cursor in seenCursors
        }
        if (added == 0 && !rolling) { exhausted = true; cursor = null }
    }

    suspend fun prefetchFallback() = load.withLock {
        if (needsFallbackPrefetch) refillRound(countEmpty = false)
    }

    private suspend fun refillRound(countEmpty: Boolean = true) {
        val owner = revision
        val feedVersion = feedStore?.restore()?.version
        if (owner != revision) return
        if (fallbackFeedVersion != feedVersion) {
            // 推荐换批以后，之前预取但未入队的候选不能追加到新列表。
            fallbackFeedVersion = feedVersion
            radioBuffer.clear()
            searchBuffer.clear()
            searchPosition = null
            lastRadioLoad = null
        }
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
        fun fresh(track: MusicTrack) = track.canStartPlayback && track.id !in known && !seenRecording(track)

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

        val added = mutableListOf<MusicTrack>()
        if (listening != null) {
            val options = (radioBuffer + searchBuffer).filter(::fresh)
            val ranked = listening.rank(options, options.map { it.id }.toSet(), pending.lastOrNull() ?: history.getOrNull(index))
            if (owner != revision) return
            for (track in ranked.take(FALLBACK_BATCH)) {
                radioBuffer.removeAll { it.id == track.id }
                searchBuffer.removeAll { it.id == track.id }
                pending.addLast(track)
                remember(track)
                added += track
            }
        } else for (buffer in listOf(radioBuffer, searchBuffer)) {
            while (added.size < FALLBACK_BATCH && buffer.isNotEmpty()) {
                val track = buffer.removeFirst()
                if (!fresh(track) || track.id in considered) continue
                pending.addLast(track)
                remember(track)
                added += track
            }
        }
        if (added.isNotEmpty()) {
            emptyCycles = 0
            val targetVersion = if (rolling) rollingFeedVersion else feedVersion
            if (targetVersion != null)
                feedStore?.appendFallback(targetVersion, added, if (listening == null) emptySet() else added.map { it.id }.toSet())
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
        recordings.clear()
        rolling = seed?.rolling == true
        rollingFeedVersion = seed?.feedVersion
        retriedCursor = false
        searchPosition = null; lastRadioLoad = null; fallbackFailure = null
        fallbackFeedVersion = null
        radioBuffer.clear(); searchBuffer.clear()
        index = -1; randomNext = null
        if (seed != null) {
            val tracks = seed.tracks.filter { it.canStartPlayback }.distinctBy { it.id }
            val selected = tracks.indexOfFirst { it.id == startAt }
            pending.addAll(if (shuffle) tracks.filterNot { it.id == startAt } else tracks.drop(selected + 1))
            tracks.forEach(::remember)
            cursor = seed.nextCursor
            exhausted = cursor == null
        }
    }

    private fun seenRecording(track: MusicTrack): Boolean {
        if (listening == null) return false
        val recording = MusicRecording.of(track)
        return recordings.any(recording::matches)
    }

    private fun remember(track: MusicTrack) {
        if (considered.add(track.id) && listening != null) recordings.add(MusicRecording.of(track))
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
