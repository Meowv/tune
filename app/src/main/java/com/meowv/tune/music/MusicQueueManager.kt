package com.meowv.tune.music

import com.meowv.tune.domain.music.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer
import java.util.Locale

internal data class MusicSelection(val token: Long, val track: MusicTrack, internal val historyIndex: Int? = null)

internal class MusicQueueManager(
    private val repository: () -> MusicRepository,
    private val batchSize: Int = 20,
    private val lowWater: Int = 8,
    private val historyLimit: Int = 100,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
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
    private var issued = 0L
    private var committed = -1L
    private var index = -1
    private var revision = 0L

    init { require(batchSize in 1..50 && lowWater >= 1 && historyLimit >= 2) }
    val canPrevious: Boolean get() = index > 0
    val canNext: Boolean get() = index < history.lastIndex || pending.isNotEmpty() || !exhausted
    val nextTrack: MusicTrack? get() = if (index < history.lastIndex) history[index + 1] else nextCandidate()
    val needsReplenish: Boolean get() = pending.size + (history.lastIndex - index).coerceAtLeast(0) < lowWater && !exhausted
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
        if (pending.isEmpty()) replenish(minimumOnly = true)
        currentCoroutineContext().ensureActive()
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
            if (page.items.size > batchSize) throw MusicException(MusicFailure.INVALID_RESPONSE)
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
        cursor = null; exhausted = false; index = -1; randomNext = null
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
        remember(considered, id)
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
}
