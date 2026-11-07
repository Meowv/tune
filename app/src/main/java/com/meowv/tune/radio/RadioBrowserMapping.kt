package com.meowv.tune.radio

import com.meowv.tune.domain.CountryCode
import com.meowv.tune.domain.RadioCountry
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object RadioBrowserMapping {
    fun countries(body: String): List<RadioCountry> = rows(body).mapNotNull { row ->
        val item = row as? JsonObject ?: return@mapNotNull null
        val code = CountryCode.parse(item.text("name")) ?: return@mapNotNull null
        val count = item.scalar("stationcount")?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        RadioCountry(code, count)
    }.groupBy { it.code }.values.map { duplicates -> duplicates.maxBy { it.stationCount } }

    fun station(row: JsonElement): Station? {
        val item = row as? JsonObject ?: return null
        val id = item.text("stationuuid") ?: return null
        val uuid = try {
            UUID.fromString(id).takeIf { it.toString().equals(id, ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        return Station(
            uuid = uuid,
            name = item.text("name")?.let(::cleanName).orEmpty(),
            countryCode = CountryCode.parse(item.text("countrycode")),
            iconUrl = safeIcon(item.text("favicon")),
            streamUrl = item.text("url"),
            resolvedStreamUrl = item.text("url_resolved"),
            codec = item.text("codec"),
            bitrate = item.scalar("bitrate")?.toIntOrNull()?.takeIf { it > 0 },
            isHls = item.scalar("hls") in setOf("true", "1")
        )
    }

    fun rows(body: String): JsonArray = try {
        Json.parseToJsonElement(body) as? JsonArray ?: throw TuneFailure(TuneError.INVALID_RESPONSE)
    } catch (_: SerializationException) {
        throw TuneFailure(TuneError.INVALID_RESPONSE)
    }

    private fun cleanName(name: String): String = name
        .replace(Regex("[\\p{Cc}\\p{Cf}\\p{Z}]+"), " ").trim().take(200)

    private fun safeIcon(value: String?): String? = value?.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty()
    }?.toString()

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.scalar(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
