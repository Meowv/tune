package com.meowv.tune.music

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.*

/** 本机保留测试所需的日汇总。日志只输出计数。 */
internal class MusicRecommendationTrial {
    private data class Day(
        val date: String,
        val top: List<String> = emptyList(),
        val automatic: Int = 0,
        val repeated: Int = 0,
        val skipped: Int = 0,
        val attempts: Int = 0,
        val starts: Int = 0
    )

    private val days = sortedMapOf<String, Day>()

    fun restore(value: String?) {
        if (value == null) return
        runCatching {
            Json.parseToJsonElement(value).jsonArray.forEach { encoded ->
                val row = encoded.jsonObject
                fun count(key: String) = row[key]?.jsonPrimitive?.intOrNull ?: 0
                val date = LocalDate.parse(row.getValue("date").jsonPrimitive.content).toString()
                val top = row.getValue("top").jsonArray.map { it.jsonPrimitive.content }
                days[date] = Day(date, top, count("automatic"), count("repeated"), count("skipped"),
                    count("attempts"), count("starts"))
            }
        }.onFailure { days.clear() }
    }

    fun displayed(atMs: Long, recordings: List<String>) = update(atMs) {
        val sample = recordings.take(20)
        if (sample.size > it.top.size) it.copy(top = sample) else it
    }

    fun attempted(atMs: Long) = update(atMs) { it.copy(attempts = it.attempts + 1) }

    fun started(atMs: Long) = update(atMs) { it.copy(starts = it.starts + 1) }

    fun recorded(event: MusicListeningEvent, repeated: Boolean) = update(event.atMs) {
        when {
            event.skipped -> it.copy(skipped = it.skipped + 1)
            event.automatic -> it.copy(automatic = it.automatic + 1, repeated = it.repeated + if (repeated) 1 else 0)
            else -> it
        }
    }

    fun encode(): String = buildJsonArray {
        days.values.forEach { day ->
            add(buildJsonObject {
                put("date", day.date)
                putJsonArray("top") { day.top.forEach { add(it) } }
                put("automatic", day.automatic)
                put("repeated", day.repeated)
                put("skipped", day.skipped)
                put("attempts", day.attempts)
                put("starts", day.starts)
            })
        }
    }.toString()

    fun summary(): String = days.values.joinToString("\n") { day ->
        val yesterday = days[LocalDate.parse(day.date).minusDays(1).toString()]?.top.orEmpty()
        val overlap = yesterday.takeIf { it.isNotEmpty() }?.intersect(day.top.toSet())?.size
        "${day.date} top=${day.top.size} overlap=${overlap ?: "-"} previous=${yesterday.size} " +
            "automatic=${day.automatic} repeated=${day.repeated} skipped=${day.skipped} attempts=${day.attempts} starts=${day.starts}"
    }

    private fun update(atMs: Long, transform: (Day) -> Day) {
        val date = Instant.ofEpochMilli(atMs).atZone(BusinessZone).toLocalDate()
        val key = date.toString()
        val cutoff = date.minusDays(7).toString()
        days.keys.removeAll { it < cutoff }
        days[key] = transform(days[key] ?: Day(key))
    }

    private companion object { val BusinessZone = ZoneId.of("Asia/Shanghai") }
}
