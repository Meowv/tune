package com.meowv.tune.network

import com.meowv.tune.domain.TuneFailure
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@JvmInline
internal value class MirrorHost private constructor(val name: String) {
    companion object {
        private val pattern = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.api\\.radio-browser\\.info")

        fun parse(value: String?): MirrorHost? =
            value?.trim()?.removeSuffix(".")?.lowercase(Locale.ROOT)?.takeIf(pattern::matches)?.let(::MirrorHost)
    }
}

internal interface LastMirrorStore {
    suspend fun read(): MirrorHost?
    suspend fun write(host: MirrorHost)
}

internal class MirrorDirectory(
    private val discover: suspend () -> List<MirrorHost>,
    private val serverList: suspend (MirrorHost) -> String,
    private val store: LastMirrorStore,
    private val nowMillis: () -> Long,
    private val seeds: List<MirrorHost> = listOf("de1", "de2").map {
        requireNotNull(MirrorHost.parse("$it.api.radio-browser.info"))
    }
) {
    private val refreshLock = Mutex()
    private val successLock = Mutex()
    private val coolingUntil = ConcurrentHashMap<MirrorHost, Long>()
    private var pool = emptyList<MirrorHost>()
    private var refreshAt = Long.MIN_VALUE
    @Volatile private var lastGood: MirrorHost? = null

    suspend fun candidates(): List<MirrorHost> = refreshLock.withLock {
        if (nowMillis() >= refreshAt) {
            if (lastGood == null) {
                try {
                    successLock.withLock {
                        if (lastGood == null) lastGood = store.read()
                    }
                } catch (_: IOException) {
                    // 缓存不可读时仍可使用 DNS 和应急种子。
                }
            }
            val discovered = withTimeoutOrNull(DISCOVERY_TIMEOUT) { discover() }.orEmpty()
            val initial = (listOfNotNull(lastGood) + discovered + pool + seeds).distinct()
            // 每个刷新周期最多请求两个节点的服务器列表，元数据失败不阻断目录请求。
            val reported = initial.take(2).firstNotNullOfOrNull { host ->
                try {
                    parseServers(serverList(host)).takeIf { it.isNotEmpty() }
                } catch (_: TuneFailure) {
                    null
                }
            }.orEmpty()
            val fresh = (discovered + reported).distinct()
            pool = (fresh.ifEmpty { pool } + seeds).distinct().take(MAX_CANDIDATES)
            refreshAt = nowMillis() + if (fresh.isNotEmpty()) TTL else FALLBACK_TTL
        }
        val now = nowMillis()
        coolingUntil.entries.removeIf { it.value <= now }
        val (healthy, cooling) = (listOfNotNull(lastGood) + pool).distinct()
            .partition { it !in coolingUntil.keys }
        healthy + cooling
    }

    fun recordFailure(host: MirrorHost) {
        coolingUntil[host] = nowMillis() + FAILURE_COOLDOWN
    }

    suspend fun recordSuccess(host: MirrorHost) = successLock.withLock {
        coolingUntil.remove(host)
        if (lastGood == host) return@withLock
        lastGood = host
        try {
            store.write(host)
        } catch (_: IOException) {
            // 写缓存失败不改变已成功的目录请求结果。
        }
    }

    private fun parseServers(body: String): List<MirrorHost> {
        val array = try {
            Json.parseToJsonElement(body) as? JsonArray
        } catch (_: SerializationException) {
            null
        }
        return array.orEmpty().mapNotNull { item ->
            val name = (item as? JsonObject)?.get("name") as? JsonPrimitive
            MirrorHost.parse(name?.takeIf { it.isString }?.content)
        }
    }

    private companion object {
        const val DISCOVERY_TIMEOUT = 2500L
        const val TTL = 15 * 60 * 1000L
        const val FALLBACK_TTL = 60 * 1000L
        const val FAILURE_COOLDOWN = 30 * 1000L
        const val MAX_CANDIDATES = 20
    }
}
