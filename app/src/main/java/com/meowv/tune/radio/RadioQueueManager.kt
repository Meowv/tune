package com.meowv.tune.radio

import com.meowv.tune.domain.QueueSelection
import com.meowv.tune.domain.RadioQueue
import com.meowv.tune.domain.RadioRepository
import com.meowv.tune.domain.ResolvedRadioScope
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.StationPage
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RadioQueueManager(private val repository: RadioRepository) : RadioQueue {
    private val stateLock = Any()
    private var session: Session? = null
    private val previousAvailable = MutableStateFlow(false)
    override val canPrevious = previousAvailable.asStateFlow()

    override fun clearScope() {
        synchronized(stateLock) {
            session = null
            previousAvailable.value = false
        }
    }

    override fun updateDirectory(scope: ResolvedRadioScope, page: StationPage) {
        synchronized(stateLock) {
            val active = session?.takeIf { it.scope == scope } ?: Session(scope).also {
                session = it
                previousAvailable.value = false
            }
            val filtered = StationPage(
                page.stations.filter { scope.country == null || it.countryCode == scope.country }.distinctBy { it.uuid },
                page.nextOffset.takeIf { scope.country != null }
            )
            if (active.page != filtered) {
                active.page = filtered
                if (scope.country == null) active.preview = filtered.stations
            }
        }
    }

    override fun select(scope: ResolvedRadioScope, uuid: UUID): QueueSelection? = synchronized(stateLock) {
        val active = session?.takeIf { it.scope == scope } ?: return@synchronized null
        val station = active.page.stations.firstOrNull { it.uuid == uuid } ?: return@synchronized null
        active.select(station)
    }

    override fun previous(scope: ResolvedRadioScope): QueueSelection? = synchronized(stateLock) {
        val active = session?.takeIf { it.scope == scope } ?: return@synchronized null
        val station = active.history.lastOrNull() ?: return@synchronized null
        active.select(station, fromHistory = true)
    }

    override suspend fun next(scope: ResolvedRadioScope, cursor: UUID?): QueueSelection? {
        val active = synchronized(stateLock) { session?.takeIf { it.scope == scope } } ?: return null
        // 网络请求只串行化当前会话的 Next，切换范围后的新会话可立即选择。
        return active.nextLock.withLock {
            val request = synchronized(stateLock) {
                if (session !== active) return@withLock null
                active.pending = null
                NextRequest(
                    revision = ++active.revision,
                    cursor = cursor ?: active.current?.uuid,
                    page = active.page,
                    preview = active.preview.also { active.preview = emptyList() },
                    excluded = active.recent.toSet() + listOfNotNull(cursor, active.current?.uuid)
                )
            }
            val country = scope.country
            if (country == null) {
                active.random.offer(request.preview)
                val station = active.random.take(request.excluded)
                currentCoroutineContext().ensureActive()
                synchronized(stateLock) {
                    if (!isCurrent(active, request)) null else station?.let { active.select(it) }
                }
            } else {
                val successor = after(request.page, request.cursor)
                val fetched = if (successor == null && request.page.nextOffset != null) {
                    appendCountryPage(repository, country, request.page)
                } else null
                currentCoroutineContext().ensureActive()
                synchronized(stateLock) {
                    if (!isCurrent(active, request)) return@synchronized null
                    if (fetched != null && active.page === request.page) active.page = fetched
                    val station = after(active.page, request.cursor)
                        ?: active.page.stations.firstOrNull().takeIf { active.page.nextOffset == null }
                    station?.let { active.select(it) }
                }
            }
        }
    }

    override fun recordSuccessfulPlayback(selection: QueueSelection): Boolean = synchronized(stateLock) {
        val active = session ?: return@synchronized false
        val pending = active.pending?.takeIf { it.selection === selection } ?: return@synchronized false
        active.pending = null
        val station = selection.station
        if (pending.fromHistory) active.history.removeLast()
        else active.current?.takeIf { it.uuid != station.uuid }?.let(active.history::addLast)
        active.current = station
        if (active.scope.country == null) {
            active.recent.remove(station.uuid)
            active.recent.addLast(station.uuid)
            if (active.recent.size > RECENT_LIMIT) active.recent.removeFirst()
        }
        previousAvailable.value = active.history.isNotEmpty()
        true
    }

    private fun isCurrent(active: Session, request: NextRequest): Boolean =
        session === active && active.revision == request.revision

    private fun after(page: StationPage, cursor: UUID?): Station? =
        page.stations.getOrNull(page.stations.indexOfFirst { it.uuid == cursor } + 1)

    private inner class Session(val scope: ResolvedRadioScope) {
        var page = StationPage(nextOffset = if (scope.country != null) 0 else null)
        var preview = emptyList<Station>()
        val nextLock = Mutex()
        val random = RandomStationPool { repository.randomGlobalStations(DIRECTORY_PAGE_SIZE) }
        val history = ArrayDeque<Station>()
        val recent = ArrayDeque<UUID>()
        var current: Station? = null
        var revision = 0L
        var pending: Pending? = null

        fun select(station: Station, fromHistory: Boolean = false): QueueSelection =
            QueueSelection(station).also {
                revision++
                pending = Pending(it, fromHistory)
            }
    }

    private data class Pending(val selection: QueueSelection, val fromHistory: Boolean)
    private data class NextRequest(
        val revision: Long,
        val cursor: UUID?,
        val page: StationPage,
        val preview: List<Station>,
        val excluded: Set<UUID>
    )

    private companion object {
        const val RECENT_LIMIT = 10
    }
}
