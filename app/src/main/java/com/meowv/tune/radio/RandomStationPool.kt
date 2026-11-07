package com.meowv.tune.radio

import com.meowv.tune.domain.Station
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// 消费服务端返回的随机顺序。由所属队列会话串行调用。
internal class RandomStationPool(private val fetch: suspend () -> List<Station>) {
    private val candidates = LinkedHashMap<UUID, Station>()

    fun offer(stations: List<Station>) {
        stations.forEach { station ->
            if (candidates.size < DIRECTORY_PAGE_SIZE) candidates.putIfAbsent(station.uuid, station)
        }
    }

    suspend fun take(excluded: Set<UUID>): Station? {
        repeat(MAX_BATCHES + 1) { attempt ->
            currentCoroutineContext().ensureActive()
            val iterator = candidates.values.iterator()
            while (iterator.hasNext()) {
                val station = iterator.next()
                iterator.remove()
                if (station.uuid !in excluded) return station
            }
            if (attempt < MAX_BATCHES) {
                val batch = fetch()
                currentCoroutineContext().ensureActive()
                offer(batch)
            }
        }
        return null
    }

    private companion object {
        const val MAX_BATCHES = 3
    }
}
