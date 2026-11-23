package com.meowv.tune.music

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.meowv.tune.domain.music.MusicTrack
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal data class MusicRecording(
    val id: String,
    val title: String,
    val artists: Set<String>,
    val durationMs: Long?,
    val edition: Set<String>,
    val isrc: String?,
    val sources: Set<String>
) {
    fun matches(other: MusicRecording): Boolean {
        if (id == other.id || sources.any(other.sources::contains)) return true
        if (edition != other.edition) return false
        if (isrc != null && other.isrc != null) return isrc == other.isrc
        val left = durationMs ?: return false
        val right = other.durationMs ?: return false
        return title.isNotEmpty() && title == other.title && artists.isNotEmpty() && artists == other.artists &&
            abs(left - right) <= maxOf(2_000, left / 50)
    }

    companion object {
        private val isrcPattern = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
        private val editionWords = mapOf(
            "live" to Regex("\\blive\\b|现场|現場|演唱会|演唱會", RegexOption.IGNORE_CASE),
            "cover" to Regex("\\bcover\\b|翻唱", RegexOption.IGNORE_CASE),
            "remix" to Regex("\\bremix\\b|重混", RegexOption.IGNORE_CASE),
            "instrumental" to Regex("\\binstrumental\\b|伴奏|纯音乐版|純音樂版", RegexOption.IGNORE_CASE)
        )
        private val variant = Regex("[（(\\[【]([^）)\\]】]+)[）)\\]】]|[-–—]\\s*([^\\s].*)$")

        fun of(track: MusicTrack): MusicRecording {
            val titleEdition = variant.findAll(track.title).flatMap { match ->
                val value = match.groupValues[1].ifEmpty { match.groupValues[2] }
                editionWords.filterValues { it.containsMatchIn(value) }.keys.asSequence()
            }.toSet()
            val sourceEdition = track.matches.firstOrNull()?.edition.orEmpty().split('+')
                .filter { it.isNotBlank() && it != "studio" }
            return MusicRecording(
                track.id, normalized(track.title), track.artists.map(::normalized).filter(String::isNotEmpty).toSet(),
                track.durationMs?.takeIf { it in 1..86_400_000 }, titleEdition + sourceEdition,
                track.isrc?.uppercase(Locale.ROOT)?.takeIf(isrcPattern::matches),
                track.matches.map { it.providerId + ":" + it.sourceTrackId }.toSet()
            )
        }

        private fun normalized(value: String) =
            Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    }
}

internal data class MusicListeningEvent(
    val attempt: String,
    val recording: MusicRecording,
    val atMs: Long,
    val completed: Boolean = false,
    val replay: Boolean = false,
    val skipped: Boolean = false,
    val automatic: Boolean = false
)

internal class MusicListeningHistory(
    private val preferences: DataStore<Preferences>? = null,
    private val now: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val reportTrial: (String) -> Unit = {}
) {
    private val lock = Mutex()
    private sealed interface Update {
        data class Heard(val event: MusicListeningEvent) : Update
        data class Displayed(val at: Long, val tracks: List<String>) : Update
        data class Attempted(val at: Long) : Update
        data class Started(val at: Long) : Update
    }

    private val pending = Channel<Update>(Channel.UNLIMITED)
    private val trial = MusicRecommendationTrial()
    private var loaded = false
    private var events = emptyList<MusicListeningEvent>()

    init {
        scope.launch {
            for (update in pending) lock.withLock {
                load()
                when (update) {
                    is Update.Heard -> apply(update.event)
                    is Update.Displayed -> trial.displayed(update.at, update.tracks)
                    is Update.Attempted -> trial.attempted(update.at)
                    is Update.Started -> trial.started(update.at)
                }
                try {
                    preferences?.edit {
                        if (update is Update.Heard) it[Key] = encode(events)
                        it[TrialKey] = trial.encode()
                    }
                } catch (_: IOException) { /* 当前进程仍可使用本地记录。 */ }
                if (update is Update.Displayed) reportTrial(trial.summary())
            }
        }
    }

    fun record(event: MusicListeningEvent) { pending.trySend(Update.Heard(event)) }
    fun attempted() { pending.trySend(Update.Attempted(now())) }
    fun started() { pending.trySend(Update.Started(now())) }

    fun displayed(tracks: List<MusicTrack>) {
        if (tracks.isNotEmpty()) pending.trySend(Update.Displayed(now(), tracks.take(20).map { track ->
            val recording = MusicRecording.of(track)
            recording.isrc ?: listOf(recording.title, recording.artists.sorted().joinToString(),
                recording.edition.sorted().joinToString(), (recording.durationMs?.div(1000) ?: 0).toString()).joinToString("|")
        }))
    }

    private fun apply(event: MusicListeningEvent) {
        val previous = events.firstOrNull { it.attempt == event.attempt }
        val related = events.filter { it.attempt != event.attempt && !it.skipped && it.recording.matches(event.recording) }
        if (previous == null)
            trial.recorded(event, related.any { it.atMs >= event.atMs - 7 * Day })
        val replay = previous?.replay == true || event.replay && related.isNotEmpty()
        events = retain(events.filterNot { it.attempt == event.attempt } + event.copy(replay = replay))
    }

    suspend fun rank(tracks: List<MusicTrack>, discoveryIds: Set<String>, previous: MusicTrack? = null): List<MusicTrack> {
        val history = snapshot()
        return withContext(Dispatchers.Default) {
            MusicRecommendationRanker.rank(tracks, discoveryIds, history, now(), previous)
        }
    }

    suspend fun freshCount(tracks: List<MusicTrack>): Int = withContext(Dispatchers.Default) {
        val cutoff = now() - Day
        val heard = snapshot().filter { !it.skipped && it.atMs >= cutoff }.map { it.recording }
        val fresh = mutableListOf<MusicRecording>()
        tracks.map(MusicRecording::of).forEach { track ->
            if (heard.none(track::matches) && fresh.none(track::matches)) fresh.add(track)
        }
        fresh.size
    }

    private suspend fun snapshot() = lock.withLock { load(); retain(events) }

    private suspend fun load() {
        if (loaded) return
        try {
            val saved = preferences?.data?.first()
            events = saved?.get(Key)?.let(::decode).orEmpty()
            trial.restore(saved?.get(TrialKey))
            loaded = true
        } catch (_: IOException) { }
    }

    private fun retain(values: List<MusicListeningEvent>) =
        values.filter { it.atMs >= now() - 30 * Day }.takeLast(500)

    private fun encode(values: List<MusicListeningEvent>) = buildJsonArray {
        values.forEach { event ->
            val entry = event.recording
            add(buildJsonObject {
                put("attempt", event.attempt)
                put("id", entry.id)
                put("title", entry.title)
                put("duration", entry.durationMs)
                put("isrc", entry.isrc)
                put("at", event.atMs)
                put("completed", event.completed)
                put("replay", event.replay)
                put("skipped", event.skipped)
                putJsonArray("artists") { entry.artists.forEach { add(it) } }
                putJsonArray("edition") { entry.edition.forEach { add(it) } }
                putJsonArray("sources") { entry.sources.forEach { add(it) } }
            })
        }
    }.toString()

    private fun decode(value: String): List<MusicListeningEvent> = runCatching {
        Json.parseToJsonElement(value).jsonArray.map { element ->
            val row = element.jsonObject
            fun strings(key: String) = row.getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()
            fun text(key: String) = row.getValue(key).jsonPrimitive.content
            MusicListeningEvent(text("attempt"), MusicRecording(text("id"), text("title"), strings("artists"),
                row["duration"]?.jsonPrimitive?.longOrNull, strings("edition"),
                row["isrc"]?.jsonPrimitive?.contentOrNull, strings("sources")), row.getValue("at").jsonPrimitive.long,
                row.getValue("completed").jsonPrimitive.boolean, row.getValue("replay").jsonPrimitive.boolean,
                row.getValue("skipped").jsonPrimitive.boolean)
        }
    }.getOrDefault(emptyList())

    private companion object {
        val Key = stringPreferencesKey("recent_music_listens")
        val TrialKey = stringPreferencesKey("music_recommendation_trial")
    }
}

private const val Day = 86_400_000L

internal object MusicRecommendationRanker {
    private data class Choice(val track: MusicTrack, val recording: MusicRecording, val last: Long?,
        val taste: Int, val discovery: Boolean, val index: Int)

    fun rank(tracks: List<MusicTrack>, discoveryIds: Set<String>, history: List<MusicListeningEvent>,
        nowMs: Long, previous: MusicTrack? = null): List<MusicTrack> {
        val artistScores = mutableMapOf<String, Int>()
        val records = mutableListOf<Choice>()
        history.forEach { event ->
            val value = when {
                event.skipped -> -1
                event.replay -> 3
                event.completed -> 2
                else -> 1
            }
            event.recording.artists.forEach { artistScores[it] = (artistScores[it] ?: 0) + value }
        }
        tracks.forEachIndexed { index, track ->
            val recording = MusicRecording.of(track)
            if (records.none { recording.matches(it.recording) }) {
                val last = history.asSequence().filter { !it.skipped && recording.matches(it.recording) }.maxOfOrNull { it.atMs }
                val taste = recording.artists.sumOf { artistScores[it] ?: 0 }
                records.add(Choice(track, recording, last, taste, track.id in discoveryIds, index))
            }
        }
        val sorted = records.sortedWith(compareBy<Choice> { tier(it.last, nowMs) }
            .thenBy { if (tier(it.last, nowMs) == 2) it.last else null }
            .thenByDescending { it.taste }
            .thenBy { it.index }).toMutableList()
        val result = ArrayList<MusicTrack>(sorted.size)
        var lastArtists = previous?.let(MusicRecording::of)?.artists.orEmpty()
        while (sorted.isNotEmpty()) {
            val fresh = sorted.filter { tier(it.last, nowMs) < 2 }
            val remaining = fresh.ifEmpty { sorted }
            val wantsDiscovery = result.size % 5 >= 3
            val otherArtist = remaining.filter { item -> item.recording.artists.none(lastArtists::contains) }
            val diverse = otherArtist.ifEmpty { remaining }
            val next = if (fresh.isEmpty()) diverse.first()
                else diverse.firstOrNull { it.discovery == wantsDiscovery } ?: diverse.first()
            result.add(next.track)
            lastArtists = next.recording.artists
            sorted.remove(next)
        }
        return result
    }

    private fun tier(last: Long?, nowMs: Long) = when {
        last != null && last >= nowMs - Day -> 2
        last != null && last >= nowMs - 7 * Day -> 1
        else -> 0
    }
}

internal class MusicListeningTracker(
    private val elapsed: () -> Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val started: () -> Unit = {},
    private val emit: (MusicListeningEvent) -> Unit
) {
    private data class Attempt(val id: String, val recording: MusicRecording, val replay: Boolean, val automatic: Boolean,
        var playingSince: Long? = null, var heard: Long = 0, var qualified: Boolean = false, var started: Boolean = false)

    private var current: Attempt? = null

    fun start(track: MusicTrack, explicit: Boolean = false, continuing: Boolean = false, automatic: Boolean = false) {
        val recording = MusicRecording.of(track)
        if (continuing && current?.recording?.matches(recording) == true) return
        observe(false)
        current = Attempt(UUID.randomUUID().toString(), recording, explicit, automatic)
    }

    fun observe(playing: Boolean) {
        val attempt = current ?: return
        val time = elapsed()
        if (playing && !attempt.started) {
            attempt.started = true
            started()
        }
        attempt.playingSince?.let { attempt.heard += (time - it).coerceAtLeast(0) }
        attempt.playingSince = time.takeIf { playing }
        if (!attempt.qualified && attempt.heard >= 30_000) {
            attempt.qualified = true
            emit(MusicListeningEvent(attempt.id, attempt.recording, now(), replay = attempt.replay, automatic = attempt.automatic))
        }
    }

    fun finish(completed: Boolean = false, earlySkip: Boolean = false) {
        observe(false)
        val attempt = current ?: return
        current = null
        val endedNaturally = completed && (attempt.recording.durationMs?.let { attempt.heard >= it * 8 / 10 }
            ?: attempt.qualified)
        if (endedNaturally) {
            emit(MusicListeningEvent(attempt.id, attempt.recording, now(), completed = true, replay = attempt.replay, automatic = attempt.automatic))
        } else if (earlySkip && attempt.heard in 1 until 15_000L) {
            emit(MusicListeningEvent(attempt.id, attempt.recording, now(), skipped = true, automatic = attempt.automatic))
        }
    }
}
