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
import kotlinx.coroutines.Dispatchers
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
                        // 快照最多 256KB，JSON 解析放到 Default，避免冷启动卡首帧。
                        val countries = withContext(Dispatchers.Default) {
                            RadioBrowserMapping.countries(it.body)
                        }
                        DirectoryValue(countries, it.stale(COUNTRY_TTL))
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
                val body = withContext(Dispatchers.Default) { encodeCountries(countries) }
                save(COUNTRIES, ticket, body)
                DirectoryValue(countries, false)
            } finally {
                finish(COUNTRIES, ticket)
            }
        }

    suspend fun stations(scope: ResolvedRadioScope): DirectoryValue<StationPage>? = mutex.withLock {
        val record = read(key(scope)) ?: return@withLock null
        val page = try {
            withContext(Dispatchers.Default) { parseStations(record.body) }
        } catch (_: SerializationException) {
            null
        } ?: return@withLock null
        DirectoryValue(page, record.stale(STATION_TTL))
    }

    suspend fun refreshStations(scope: ResolvedRadioScope, fetch: suspend () -> StationPage): StationPage {
        val key = key(scope)
        val ticket = mutex.withLock { Any().also { writes[key] = it } }
        try {
            val page = fetch()
            val body = withContext(Dispatchers.Default) { encodePage(page) }
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
                    // 排序只取时间戳，避免对每条 256KB 的 body 做 split 复制。
                    others.sortedByDescending { timestampOf(it.value as? String) }
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

    private fun parseStations(body: String): StationPage? {
        val json = Json.parseToJsonElement(body) as? JsonObject ?: return null
        val rows = json["stations"] as? JsonArray ?: return null
        val next = (json["nextOffset"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        return StationPage(rows.mapNotNull(RadioBrowserMapping::station), next)
    }

    private fun encodePage(page: StationPage): String = buildJsonObject {
        put("stations", JsonArray(page.stations.map(::directoryFields)))
        page.nextOffset?.let { put("nextOffset", it) }
    }.toString()

    private fun encodeCountries(countries: List<RadioCountry>): String = JsonArray(countries.map {
        buildJsonObject {
            put("name", it.code.value)
            put("stationcount", it.stationCount)
        }
    }).toString()

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
        if (value == null) return null
        val versionEnd = value.indexOf('|')
        if (versionEnd <= 0) return null
        val timestampEnd = value.indexOf('|', versionEnd + 1)
        if (timestampEnd < 0) return null
        if (value.substring(0, versionEnd) != VERSION) return null
        val timestamp = value.substring(versionEnd + 1, timestampEnd).toLongOrNull()?.takeIf { it >= 0 }
            ?: return null
        if (value.length - timestampEnd - 1 > MAX_CHARS) return null
        return Record(timestamp, value.substring(timestampEnd + 1))
    }

    // 只解析头部时间戳，不复制 body；用于容量淘汰排序。
    private fun timestampOf(value: String?): Long? {
        if (value == null) return null
        val versionEnd = value.indexOf('|')
        if (versionEnd <= 0 || value.substring(0, versionEnd) != VERSION) return null
        val timestampEnd = value.indexOf('|', versionEnd + 1)
        if (timestampEnd < 0) return null
        return value.substring(versionEnd + 1, timestampEnd).toLongOrNull()?.takeIf { it >= 0 }
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
