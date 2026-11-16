package com.meowv.tune.music

import com.meowv.tune.domain.music.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class MusicSelection(val token: Long, val track: MusicTrack, internal val historyIndex: Int? = null)

internal class MusicQueueManager(
    private val repository: () -> MusicRepository,
    private val batchSize: Int = 20,
    private val lowWater: Int = 8,
    private val historyLimit: Int = 100
) {
    private val pending = ArrayDeque<MusicTrack>()
    private val history = mutableListOf<MusicTrack>()
    private val considered = LinkedHashSet<String>()
    private val load = Mutex()
    private var cursor: String? = null
    private var exhausted = false
    private var issued = 0L
    private var committed = -1L
    private var index = -1

    init { require(batchSize in 1..50 && lowWater >= 1 && historyLimit >= 2) }
    val canPrevious: Boolean get() = index > 0
    val canNext: Boolean get() = index < history.lastIndex || pending.isNotEmpty() || !exhausted

    fun invalidate() { issued++ }
    fun renew(selection: MusicSelection) = selection.copy(token = ++issued)

    fun select(track: MusicTrack): MusicSelection {
        pending.removeAll { it.id == track.id }
        remember(track.id)
        return MusicSelection(++issued, track)
    }

    suspend fun next(): MusicSelection? {
        if (index < history.lastIndex) return MusicSelection(++issued, history[index + 1], index + 1)
        if (pending.isEmpty()) replenish()
        currentCoroutineContext().ensureActive()
        return pending.removeFirstOrNull()?.let { MusicSelection(++issued, it) }
    }

    fun previous(): MusicSelection? = if (canPrevious) MusicSelection(++issued, history[index - 1], index - 1) else null

    suspend fun replenish() = load.withLock {
        // 单次最多读取两页；重复游标或全重复目录不能造成无界补队列。
        repeat(2) {
            if (pending.size >= lowWater || exhausted) return@withLock
            val requestedCursor = cursor
            val page = repository().recommendations(batchSize, requestedCursor)
            currentCoroutineContext().ensureActive()
            if (page.items.size > batchSize) throw MusicException(MusicFailure.INVALID_RESPONSE)
            val known = considered + pending.map { it.id } + history.map { it.id }
            page.items.distinctBy { it.id }.filterNot { it.id in known }.forEach {
                pending.addLast(it)
                remember(it.id)
            }
            cursor = page.nextCursor
            exhausted = cursor == null || cursor == requestedCursor
        }
    }

    fun recordSuccessfulPlayback(selection: MusicSelection): Boolean {
        if (selection.token != issued || committed == selection.token) return false
        committed = selection.token
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

    fun reset() {
        invalidate()
        pending.clear(); history.clear(); considered.clear()
        cursor = null; exhausted = false; index = -1
    }

    private fun remember(id: String) {
        considered.add(id)
        while (considered.size > historyLimit) considered.remove(considered.first())
    }
}
