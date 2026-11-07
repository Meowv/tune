package com.meowv.tune.radio

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.meowv.tune.domain.RadioCountry
import com.meowv.tune.domain.ResolvedRadioScope
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.StationPage
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

data class DirectoryValue<T>(val value: T, val stale: Boolean, val error: TuneError? = null)

class DirectoryCache internal constructor(
    private val store: DataStore<Preferences>,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val countryRequests = Mutex()
    private val memory = LinkedHashMap<String, Record>()
    private val writes = mutableMapOf<String, Any>()

    suspend fun loadCountries(force: Boolean = false, fetch: suspend () -> List<RadioCountry>): DirectoryValue<List<RadioCountry>> =
        countryRequests.withLock {
            val (previous, ticket) = start(COUNTRIES)
            try {
                val cached = previous?.let {
                    try {
                        DirectoryValue(RadioBrowserMapping.countries(it.body), it.stale(COUNTRY_TTL))
                    } catch (_: TuneFailure) {
                        null
                    }
                }
                if (!force && cached != null && !cached.stale) return@withLock cached
                val countries = try {
                    fetch()
                } catch (failure: TuneFailure) {
                    if (cached == null || !mutex.withLock { writes[COUNTRIES] === ticket }) throw failure
                    return@withLock cached.copy(stale = true, error = failure.kind)
                }
                val body = JsonArray(countries.map {
                    buildJsonObject {
                        put("name", it.code.value)
                        put("stationcount", it.stationCount)
                    }
                }).toString()
                save(COUNTRIES, ticket, body)
                DirectoryValue(countries, false)
            } finally {
                finish(COUNTRIES, ticket)
            }
        }

    suspend fun stations(scope: ResolvedRadioScope): DirectoryValue<StationPage>? = mutex.withLock {
        val record = read(key(scope)) ?: return@withLock null
        val page = try {
            val json = Json.parseToJsonElement(record.body) as? JsonObject ?: return@withLock null
            val rows = json["stations"] as? JsonArray ?: return@withLock null
            val next = (json["nextOffset"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
            StationPage(rows.mapNotNull(RadioBrowserMapping::station), next)
        } catch (_: SerializationException) {
            return@withLock null
        }
        DirectoryValue(page, record.stale(STATION_TTL))
    }

    suspend fun refreshStations(scope: ResolvedRadioScope, fetch: suspend () -> StationPage): StationPage {
        val key = key(scope)
        val ticket = mutex.withLock { Any().also { writes[key] = it } }
        try {
            val page = fetch()
            val body = buildJsonObject {
                put("stations", JsonArray(page.stations.map(::directoryFields)))
                page.nextOffset?.let { put("nextOffset", it) }
            }.toString()
            save(key, ticket, body)
            return page
        } finally {
            finish(key, ticket)
        }
    }

    suspend fun clear() = mutex.withLock {
        withContext(NonCancellable) {
            store.edit { it.clear() }
            memory.clear()
            writes.clear()
        }
    }

    private suspend fun start(key: String): Pair<Record?, Any> = mutex.withLock {
        val record = read(key)
        val ticket = Any()
        writes[key] = ticket
        record to ticket
    }

    private suspend fun finish(key: String, ticket: Any) = withContext(NonCancellable) {
        mutex.withLock {
            if (writes[key] === ticket) writes.remove(key)
        }
    }

    private suspend fun read(key: String): Record? {
        memory[key]?.let { return it }
        val record = try {
            decode(store.data.first().asMap()[stringPreferencesKey(key)] as? String)
        } catch (_: IOException) {
            null
        }
        if (record != null) remember(key, record)
        return record
    }

    private suspend fun save(key: String, ticket: Any, body: String) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (writes[key] !== ticket || body.length > MAX_CHARS) return@withLock
        val record = Record(nowMillis(), body)
        withContext(NonCancellable) {
            try {
                store.edit { prefs ->
                    val entry = stringPreferencesKey(key)
                    prefs[entry] = "$VERSION|${record.timestamp}|${record.body}"
                    // 自动范围离线恢复仍需要国家目录，容量淘汰只针对台站快照。
                    val others = prefs.asMap().entries.filter { it.key != entry && it.key.name != COUNTRIES }
                    val pinned = if (key != COUNTRIES && prefs.contains(stringPreferencesKey(COUNTRIES))) 1 else 0
                    others.sortedByDescending { decode(it.value as? String)?.timestamp }
                        .drop(MAX_ENTRIES - 1 - pinned).forEach { prefs.remove(it.key) }
                }
            } catch (_: IOException) {
                // 磁盘失败时保留内存快照，目录请求结果仍可使用。
            }
            remember(key, record)
        }
    }

    private fun remember(key: String, record: Record) {
        memory.remove(key)
        memory[key] = record
        if (memory.size > MAX_ENTRIES) memory.remove(memory.keys.first { it != COUNTRIES })
    }

    private fun key(scope: ResolvedRadioScope): String =
        "stations_${scope.scope.mode.name}_${scope.country?.value.orEmpty()}"

    private fun directoryFields(station: Station) = buildJsonObject {
        put("stationuuid", station.uuid.toString())
        put("name", station.name)
        station.countryCode?.let { put("countrycode", it.value) }
        // 缓存只保留目录字段，播放源由 UUID 重新解析。
        station.iconUrl?.let { put("favicon", it.substringBefore('?').substringBefore('#')) }
        station.codec?.let { put("codec", it) }
        station.bitrate?.let { put("bitrate", it) }
        put("hls", station.isHls)
    }

    private fun decode(value: String?): Record? {
        val parts = value?.split('|', limit = 3) ?: return null
        if (parts.size != 3 || parts[0] != VERSION || parts[2].length > MAX_CHARS) return null
        val timestamp = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return Record(timestamp, parts[2])
    }

    private inner class Record(val timestamp: Long, val body: String) {
        fun stale(ttl: Long): Boolean = nowMillis() - timestamp !in 0 until ttl
    }

    private companion object {
        const val VERSION = "1"
        const val COUNTRIES = "countries"
        const val COUNTRY_TTL = 24 * 60 * 60 * 1000L
        const val STATION_TTL = 20 * 60 * 1000L
        const val MAX_ENTRIES = 12
        const val MAX_CHARS = 256_000
    }
}
